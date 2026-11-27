package com.sgalgodesk.desk.application.algo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockOptionChainService;
import com.sgalgodesk.desk.config.InstrumentsProperties;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.EntrySettings;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.LegConfig;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.OverallSettings;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.StrategyConfig;
import com.sgalgodesk.desk.application.algo.AlgoStrikeResolve.Moneyness;
import com.sgalgodesk.desk.application.algo.AlgoStrikeResolve.ParsedCriteria;
import com.sgalgodesk.desk.application.algo.AlgoStrategyService.ResolvedLeg;
import com.sgalgodesk.desk.application.algo.AlgoStrategyService.RunningStrategyContext;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionSide;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderResult;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.quote.QuoteService;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;
import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Background loop for RUNNING algo strategies.
 * Implements: timed entry of user legs, fill-confirmed LIVE entry with BUY-then-SELL
 * for same-type spreads, per-leg SL-M after fill, overall SL / target / trailing, and
 * ordered SELL-then-BUY exits (cancel resting SL first).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlgoStrategyEngine {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	/** Suppress identical entry-time ENGINE_ERRORs and skip resolve retries during this window. */
	private static final Duration ENTRY_ERROR_COOLDOWN = Duration.ofSeconds(60);

	private final AlgoStrategyService algoStrategyService;
	private final AlgoLivePriceLease algoLivePriceLease;
	private final MstockOptionChainService optionChainService;
	private final MstockInstrumentService instrumentService;
	private final MstockMarketCalendarService marketCalendar;
	private final OrderService orderService;
	private final QuoteService quoteService;
	private final InstrumentsProperties instrumentsProperties;

	private final ConcurrentHashMap<UUID, EntryErrorState> entryErrorByStrategy = new ConcurrentHashMap<>();
	/** Prevents overlapping scheduled ticks while entry/exit work is still in flight. */
	private final AtomicBoolean tickInFlight = new AtomicBoolean(false);
	/** Per-strategy single-flight guard (defense in depth with DB ENTERING claim). */
	private final ConcurrentHashMap<UUID, Boolean> strategyInFlight = new ConcurrentHashMap<>();

	private record EntryErrorState(String message, Instant cooldownUntil) {
	}

	@Scheduled(fixedDelayString = "500", initialDelayString = "5000")
	public void tick() {
		if (!tryBeginTick()) {
			return;
		}
		algoStrategyService.listRunningStrategies()
				.concatMap(this::processOne)
				.onErrorContinue((err, ignored) -> log.warn("Algo engine tick error: {}", err.toString()))
				.then()
				.doFinally(signal -> endTick())
				.subscribe();
	}

	/** Single-flight gate for {@link #tick()}; package-visible for tests. */
	boolean tryBeginTick() {
		return tickInFlight.compareAndSet(false, true);
	}

	void endTick() {
		tickInFlight.set(false);
	}

	private Mono<Void> processOne(RunningStrategyContext ctx) {
		UUID strategyId = ctx.strategy().id();
		if (strategyInFlight.putIfAbsent(strategyId, Boolean.TRUE) != null) {
			return Mono.empty();
		}
		return algoLivePriceLease.ensure(ctx.strategy().userId(), livePriceKeys(ctx))
				.then(Mono.defer(() -> doProcess(ctx)))
				.onErrorResume(err -> handleEngineError(ctx, err))
				.doFinally(signal -> strategyInFlight.remove(strategyId));
	}

	private Mono<Void> handleEngineError(RunningStrategyContext ctx, Throwable err) {
		String msg = err.getMessage() != null ? err.getMessage() : err.toString();
		log.warn("Algo engine failed for strategy {}: {}", ctx.strategy().id(), err.toString());
		UUID strategyId = ctx.strategy().id();
		Instant now = Instant.now();
		EntryErrorState prev = entryErrorByStrategy.get(strategyId);
		boolean sameMessage = prev != null && msg.equals(prev.message());
		boolean inCooldown = prev != null && now.isBefore(prev.cooldownUntil());
		entryErrorByStrategy.put(strategyId, new EntryErrorState(msg, now.plus(ENTRY_ERROR_COOLDOWN)));
		if (sameMessage && inCooldown) {
			return Mono.empty();
		}
		return algoStrategyService.logEvent(
				strategyId,
				ctx.run().id(),
				ctx.strategy().userId(),
				"ENGINE_ERROR",
				msg).then();
	}

	private void clearEntryError(UUID strategyId) {
		if (strategyId != null) {
			entryErrorByStrategy.remove(strategyId);
		}
	}

	private boolean isEntryResolveCoolingDown(UUID strategyId) {
		EntryErrorState state = entryErrorByStrategy.get(strategyId);
		return state != null && Instant.now().isBefore(state.cooldownUntil());
	}

	private static Set<String> livePriceKeys(RunningStrategyContext ctx) {
		Set<String> keys = new HashSet<>();
		keys.add(AlgoStrikeResolve.NIFTY_CMP_KEY);
		if (ctx.openTrades() != null) {
			for (AlgoStrategyTrade trade : ctx.openTrades()) {
				if (trade != null && trade.instrumentKey() != null && !trade.instrumentKey().isBlank()) {
					keys.add(trade.instrumentKey());
				}
			}
		}
		return keys;
	}

	private Mono<Void> doProcess(RunningStrategyContext ctx) {
		LocalTime now = LocalTime.now(IST);
		String state = ctx.run().lifecycleState();
		if ("WAITING_ENTRY".equals(state) || "ENTERING".equals(state)) {
			if (!marketCalendar.isSessionOpenNow()) {
				return Mono.empty();
			}
			return maybeEnter(ctx, now);
		}
		if ("ACTIVE".equals(state)) {
			if (!marketCalendar.isSessionOpenNow()) {
				return Mono.empty();
			}
			return manageActive(ctx, now);
		}
		return Mono.empty();
	}

	private Mono<Void> maybeEnter(RunningStrategyContext ctx, LocalTime now) {
		StrategyConfig config = ctx.config();
		EntrySettings entry = config.entry();
		LocalDate today = LocalDate.now(IST);
		LocalDate runDate = ctx.run().runDate();
		// Re-armed runs target a future trading day — do nothing until that day.
		if (runDate != null && runDate.isAfter(today)) {
			return Mono.empty();
		}
		LocalTime entryTime = parseTime(entry.entryTime());
		LocalTime exitTime = parseTime(entry.exitTime());
		if (entryTime == null || now.isBefore(entryTime)) {
			return Mono.empty();
		}
		if (exitTime != null && !now.isBefore(exitTime)) {
			// Past exit on this run's day: end session and re-arm (never drop to SAVED).
			return algoStrategyService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "EXIT_TIME")
					.then();
		}
		if (entry.stopMonitoringAfterEnabled()) {
			LocalTime stopMon = parseTime(entry.stopMonitoringAfterTime());
			if (stopMon != null && now.isAfter(stopMon)) {
				return algoStrategyService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "STOP_MONITORING")
						.then();
			}
		}
		int expectedLegs = config.legs() != null ? config.legs().size() : 0;
		if (hasAllConfiguredLegsOpen(expectedLegs, ctx.openTrades())) {
			return algoStrategyService.markRunActive(ctx.strategy(), ctx.run())
					.then(algoStrategyService.logEvent(
							ctx.strategy().id(),
							ctx.run().id(),
							ctx.strategy().userId(),
							"ENTRY_FILLED",
							"Reconciled " + expectedLegs + " open leg(s); marked ACTIVE"))
					.doOnSuccess(v -> clearEntryError(ctx.strategy().id()))
					.then();
		}
		if (isEntryResolveCoolingDown(ctx.strategy().id())) {
			return Mono.empty();
		}
		return resolveAndEnter(ctx);
	}

	private Mono<Void> resolveAndEnter(RunningStrategyContext ctx) {
		StrategyConfig config = ctx.config();
		String underlyingKey = resolveUnderlyingKey(config.instrument().index());
		boolean sameTypeSpread = AlgoLegOrdering.isSameTypeSpread(config.legs());
		UUID userId = ctx.strategy().userId();
		String index = config.instrument() != null && config.instrument().index() != null
				&& !config.instrument().index().isBlank()
						? config.instrument().index()
						: "NIFTY";
		Mono<Boolean> claim = "ENTERING".equals(ctx.run().lifecycleState())
				? Mono.just(true)
				: algoStrategyService.tryClaimEntry(ctx.run());
		return claim.flatMap(claimed -> {
			if (!Boolean.TRUE.equals(claimed)) {
				return Mono.empty();
			}
			return resolveExpiryDate(userId, index, config.legs())
					.flatMap(expiryStr -> {
						LocalDate expiryDate = LocalDate.parse(expiryStr);
						return loadChainOrEmpty(userId, underlyingKey, expiryStr)
								.flatMap(chainHold -> resolveLegs(ctx, expiryDate, chainHold.chain(), chainHold.error())
										.flatMap(draft -> {
											List<ResolvedLeg> ordered = AlgoLegOrdering.orderForEntry(
													draft, sameTypeSpread);
											List<ResolvedLeg> missing = missingEntryLegs(
													ordered, ctx.openTrades());
											if (missing.isEmpty()) {
												return algoStrategyService.markRunActive(
																ctx.strategy(), ctx.run())
														.then(algoStrategyService.logEvent(
																ctx.strategy().id(),
																ctx.run().id(),
																ctx.strategy().userId(),
																"ENTRY_FILLED",
																"All legs already open; marked ACTIVE"))
														.doOnSuccess(v -> clearEntryError(
																ctx.strategy().id()))
														.then();
											}
											if ("LIVE".equalsIgnoreCase(ctx.run().executionMode())) {
												return enterLiveSequential(ctx, missing);
											}
											return enterPaper(ctx, missing);
										}));
					})
					.onErrorResume(err -> algoStrategyService.releaseEntryClaimIfIdle(ctx.run())
							.then(Mono.error(err)));
		});
	}

	/** Legs in entry order that do not yet have an OPEN trade for this run. */
	static List<ResolvedLeg> missingEntryLegs(List<ResolvedLeg> ordered, List<AlgoStrategyTrade> openTrades) {
		Set<Integer> openLegs = openLegIndexes(openTrades);
		if (ordered == null || ordered.isEmpty()) {
			return List.of();
		}
		return ordered.stream().filter(leg -> !openLegs.contains(leg.legIndex())).toList();
	}

	/** True when every configured leg index has at least one OPEN trade. */
	static boolean hasAllConfiguredLegsOpen(int expectedLegCount, List<AlgoStrategyTrade> openTrades) {
		if (expectedLegCount <= 0) {
			return false;
		}
		Set<Integer> openLegs = openLegIndexes(openTrades);
		for (int i = 0; i < expectedLegCount; i++) {
			if (!openLegs.contains(i)) {
				return false;
			}
		}
		return true;
	}

	private static Set<Integer> openLegIndexes(List<AlgoStrategyTrade> openTrades) {
		if (openTrades == null || openTrades.isEmpty()) {
			return Set.of();
		}
		return openTrades.stream()
				.filter(t -> t != null && "OPEN".equals(t.status()))
				.map(AlgoStrategyTrade::legIndex)
				.collect(Collectors.toSet());
	}

	private record ChainHold(OptionChainResponse chain, String error) {
	}

	/** Chain success, or empty hold when API errors / empty strikes (CMP fallback). */
	private Mono<ChainHold> loadChainOrEmpty(UUID userId, String underlyingKey, String expiry) {
		return optionChainService.getChain(userId, underlyingKey, expiry)
				.map(chain -> {
					if (chain == null || chain.strikes() == null || chain.strikes().isEmpty()) {
						return new ChainHold(null, "empty option chain");
					}
					return new ChainHold(chain, null);
				})
				.onErrorResume(err -> {
					log.warn("Option chain failed; using CMP fallback: {}", err.toString());
					return Mono.just(new ChainHold(null,
							err.getMessage() != null ? err.getMessage() : err.toString()));
				});
	}

	private Mono<List<ResolvedLeg>> resolveLegs(
			RunningStrategyContext ctx,
			LocalDate expiryDate,
			OptionChainResponse chain,
			String chainError) {
		StrategyConfig config = ctx.config();
		boolean needsPremium = config.legs().stream().anyMatch(leg -> {
			ParsedCriteria p = AlgoStrikeResolve.parseCriteria(leg.strikeCriteria(), leg.strikeParam1());
			return p.moneyness() == Moneyness.PREMIUM;
		});
		if (chain == null && needsPremium) {
			return Mono.error(new IllegalStateException(
					"Closest Premium requires option chain"
							+ (chainError != null ? " (" + chainError + ")" : "")));
		}

		Mono<Double> spotMono;
		if (chain != null && chain.underlyingSpot() != null && chain.underlyingSpot() > 0) {
			spotMono = Mono.just(chain.underlyingSpot());
		}
		else {
			spotMono = quoteService.ltp(ctx.strategy().userId(), List.of(AlgoStrikeResolve.NIFTY_CMP_KEY))
					.map(res -> res.prices().getOrDefault(AlgoStrikeResolve.NIFTY_CMP_KEY, 0.0))
					.flatMap(cmp -> cmp > 0
							? Mono.just(cmp)
							: Mono.error(new IllegalStateException("Could not fetch NIFTY CMP for strike fallback")));
		}

		return spotMono.flatMap(spot -> {
			List<Double> listed = chain != null
					? chain.strikes().stream().map(OptionStrikeRow::strike).sorted().toList()
					: List.of();
			double step = listed.size() >= 2
					? AlgoStrikeResolve.medianStep(listed)
					: AlgoStrikeResolve.NIFTY_STRIKE_STEP;
			Integer chainLot = chain != null && chain.lotSize() != null && chain.lotSize() > 0
					? chain.lotSize()
					: null;

			Mono<Void> logFallback = chain == null
					? algoStrategyService.logEvent(
							ctx.strategy().id(),
							ctx.run().id(),
							ctx.strategy().userId(),
							"STRIKE_FALLBACK_CMP",
							"CMP=" + spot + " step=" + step
									+ (chainError != null ? " chainError=" + chainError : ""))
					: Mono.empty();

			// Always prefer script-master lot sizes when available
			Mono<Void> ensureMaster = instrumentService.ensureLoaded(ctx.strategy().userId()).then();

			String underSym = config.instrument() != null && config.instrument().index() != null
					&& !config.instrument().index().isBlank()
							? config.instrument().index()
							: "NIFTY";

			return logFallback.then(ensureMaster).then(Mono.fromCallable(() -> {
				List<ResolvedLeg> draft = new ArrayList<>();
				for (int i = 0; i < config.legs().size(); i++) {
					LegConfig leg = config.legs().get(i);
					boolean call = AlgoStrikeResolve.isCall(leg.optionType());
					Double wantedStrike = AlgoStrikeResolve.resolveNumericStrike(
							leg.strikeCriteria(),
							leg.strikeParam1(),
							call,
							spot,
							step,
							listed.isEmpty() ? null : listed);
					ResolvedInstrument ri = resolveLegInstrument(
							chain, listed, spot, step, expiryDate, underSym, leg);
					if (ri == null || ri.instrumentKey() == null) {
						throw new IllegalStateException("Could not resolve instrument for leg " + (i + 1)
								+ " strike criteria=" + leg.strikeCriteria()
								+ " type=" + (call ? "CE" : "PE")
								+ " strike=" + wantedStrike
								+ " expiry=" + expiryDate
								+ (chainError != null ? " chainError=" + chainError : ""));
					}
					assertUnderlyingMatch(underSym, ri.instrumentKey(), ri.tradingSymbol());
					Integer catalogLot = null;
					Optional<InstrumentRecord> catalog = instrumentService.findByKey(ri.instrumentKey());
					if (catalog.isPresent() && catalog.get().lotSize() != null && catalog.get().lotSize() > 0) {
						catalogLot = catalog.get().lotSize();
					}
					String index = config.instrument() != null ? config.instrument().index() : null;
					Integer configLot = instrumentsProperties.configuredLotSize(index);
					int lot = AlgoStrikeResolve.resolveLotSize(catalogLot, ri.lotSize(), chainLot, configLot);
					if (AlgoStrikeResolve.usedLotSizeFallback(catalogLot, ri.lotSize(), chainLot)) {
						log.warn("Algo lotSize fallback for {} (index={}): using {}",
								ri.instrumentKey(),
								index,
								lot);
					}
					int qty = Math.max(1, leg.lots()) * lot;
					String optType = AlgoStrikeResolve.isCall(leg.optionType()) ? "CE" : "PE";
					draft.add(new ResolvedLeg(
							i,
							ri.instrumentKey(),
							ri.tradingSymbol(),
							AlgoLegOrdering.normalizeSide(leg.position()),
							qty,
							ri.ltp() != null ? ri.ltp() : 0.0,
							null,
							null,
							null,
							lot,
							ri.strike(),
							expiryDate != null ? expiryDate.toString() : null,
							optType));
				}
				return draft;
			}));
		});
	}

	private record ResolvedInstrument(
			String instrumentKey,
			String tradingSymbol,
			Double ltp,
			Integer lotSize,
			Double strike) {
	}

	private ResolvedInstrument resolveLegInstrument(
			OptionChainResponse chain,
			List<Double> listed,
			double spot,
			double step,
			LocalDate expiryDate,
			String underSym,
			LegConfig leg) {
		boolean call = AlgoStrikeResolve.isCall(leg.optionType());
		ParsedCriteria parsed = AlgoStrikeResolve.parseCriteria(leg.strikeCriteria(), leg.strikeParam1());

		if (parsed.moneyness() == Moneyness.PREMIUM) {
			if (chain == null || leg.strikeParam1() == null) {
				return null;
			}
			return pickByPremiumResolved(chain, call, leg.strikeParam1());
		}

		Double strike = AlgoStrikeResolve.resolveNumericStrike(
				leg.strikeCriteria(),
				leg.strikeParam1(),
				call,
				spot,
				step,
				listed.isEmpty() ? null : listed);
		if (strike == null) {
			return null;
		}

		if (chain != null) {
			OptionStrikeRow row = chain.strikes().stream()
					.filter(r -> {
						OptionSide side = call ? r.call() : r.put();
						return side != null && side.instrumentKey() != null;
					})
					.min((a, b) -> Double.compare(Math.abs(a.strike() - strike), Math.abs(b.strike() - strike)))
					.orElse(null);
			if (row != null) {
				OptionSide side = call ? row.call() : row.put();
				if (side != null && side.instrumentKey() != null) {
					return new ResolvedInstrument(
							side.instrumentKey(),
							side.tradingSymbol(),
							side.ltp(),
							side.lotSize(),
							row.strike());
				}
			}
		}

		// Script-master fallback (also used when chain threw / empty)
		Optional<InstrumentRecord> found = instrumentService.findOption(
				underSym, expiryDate, call ? "CE" : "PE", strike);
		if (found.isEmpty()) {
			return null;
		}
		InstrumentRecord rec = found.get();
		Double recStrike = rec.strikePrice() != null ? rec.strikePrice() : strike;
		return new ResolvedInstrument(rec.instrumentKey(), rec.tradingSymbol(), null, rec.lotSize(), recStrike);
	}

	private Mono<Void> enterPaper(RunningStrategyContext ctx, List<ResolvedLeg> ordered) {
		List<String> keys = ordered.stream().map(ResolvedLeg::instrumentKey).toList();
		StrategyConfig config = ctx.config();
		return quoteService.ltp(ctx.strategy().userId(), keys)
				.map(res -> res.prices())
				.onErrorReturn(Map.of())
				.flatMap(prices -> {
					List<ResolvedLeg> priced = new ArrayList<>();
					for (ResolvedLeg leg : ordered) {
						double px = prices.getOrDefault(leg.instrumentKey(), leg.entryPrice());
						LegConfig legCfg = config.legs().get(leg.legIndex());
						Double trigger = null;
						if (legCfg.slEnabled()) {
							trigger = AlgoSlMath.computeSlTrigger(leg.side(), px, legCfg.slUnit(), legCfg.slValue());
						}
						priced.add(new ResolvedLeg(
								leg.legIndex(),
								leg.instrumentKey(),
								leg.tradingSymbol(),
								leg.side(),
								leg.quantity(),
								px,
								null,
								null,
								trigger,
								leg.lotSize(),
								leg.strike(),
								leg.expiry(),
								leg.optionType()));
					}
					return algoStrategyService.enterLegs(ctx.strategy(), ctx.run(), priced, "PAPER")
							.doOnSuccess(v -> clearEntryError(ctx.strategy().id()));
				});
	}

	/**
	 * LIVE entry: one leg at a time, wait for fill, place SL-M, abort+unwind on failure.
	 */
	private Mono<Void> enterLiveSequential(RunningStrategyContext ctx, List<ResolvedLeg> ordered) {
		List<AlgoStrategyTrade> filled = new ArrayList<>();
		return Flux.fromIterable(ordered)
				.concatMap(leg -> enterOneLiveLeg(ctx, leg)
						.doOnNext(filled::add)
						.then())
				.then(algoStrategyService.markRunActive(ctx.strategy(), ctx.run())
						.then(algoStrategyService.logEvent(
								ctx.strategy().id(),
								ctx.run().id(),
								ctx.strategy().userId(),
								"ENTRY_FILLED",
								"Entered " + ordered.size() + " leg(s) in LIVE"))
						.doOnSuccess(v -> clearEntryError(ctx.strategy().id())))
				.onErrorResume(err -> {
					log.error("LIVE entry failed for strategy {}: {}", ctx.strategy().id(), err.toString());
					return algoStrategyService.logEvent(
									ctx.strategy().id(),
									ctx.run().id(),
									ctx.strategy().userId(),
									"ENTRY_FAILED",
									err.getMessage() != null ? err.getMessage() : err.toString())
							.then(algoStrategyService.unwindPartialLiveEntry(
									ctx.strategy(), ctx.run(), ctx.strategy().userId(), filled, "ENTRY_FAILED"))
							.then();
				});
	}

	private Mono<AlgoStrategyTrade> enterOneLiveLeg(RunningStrategyContext ctx, ResolvedLeg leg) {
		UUID userId = ctx.strategy().userId();
		String tag = "algo-" + ctx.strategy().id().toString().substring(0, 8);
		PlaceOrderBody entryOrder = new PlaceOrderBody(
				leg.instrumentKey(),
				leg.side(),
				leg.quantity(),
				"I",
				"MARKET",
				"DAY",
				0.0,
				null,
				tag,
				Boolean.TRUE);
		LegConfig legCfg = ctx.config().legs().get(leg.legIndex());
		return orderService.place(userId, entryOrder)
				.flatMap(placed -> {
					String orderId = firstOrderId(placed);
					if (orderId == null) {
						return Mono.error(new BrokerUnavailable(
								"No order id returned for leg " + (leg.legIndex() + 1)
										+ " status=" + placed.status()));
					}
					if (placed.status() != null && "error".equalsIgnoreCase(placed.status())) {
						return Mono.error(new BrokerUnavailable(
								"Entry order failed for leg " + (leg.legIndex() + 1) + ": " + placed.raw()));
					}
					return orderService.waitUntilComplete(userId, orderId)
							.flatMap(fill -> {
								double fillPx = fill.averagePrice() > 0
										? fill.averagePrice()
										: leg.entryPrice();
								if (!(fillPx > 0)) {
									return quoteService.ltp(userId, List.of(leg.instrumentKey()))
											.map(res -> res.prices().getOrDefault(leg.instrumentKey(), 0.0))
											.onErrorReturn(0.0)
											.flatMap(ltp -> persistLiveLeg(ctx, leg, orderId, ltp > 0 ? ltp : fillPx, legCfg));
								}
								return persistLiveLeg(ctx, leg, orderId, fillPx, legCfg);
							});
				});
	}

	private Mono<AlgoStrategyTrade> persistLiveLeg(
			RunningStrategyContext ctx,
			ResolvedLeg leg,
			String orderId,
			double fillPx,
			LegConfig legCfg) {
		Double trigger = null;
		if (legCfg.slEnabled()) {
			trigger = AlgoSlMath.computeSlTrigger(leg.side(), fillPx, legCfg.slUnit(), legCfg.slValue());
		}
		ResolvedLeg filled = new ResolvedLeg(
				leg.legIndex(),
				leg.instrumentKey(),
				leg.tradingSymbol(),
				leg.side(),
				leg.quantity(),
				fillPx,
				orderId,
				null,
				trigger,
				leg.lotSize(),
				leg.strike(),
				leg.expiry(),
				leg.optionType());
		return algoStrategyService.insertOpenTrade(ctx.strategy(), ctx.run(), filled, "LIVE")
				.flatMap(trade -> placeLegStopLoss(ctx, trade));
	}

	private Mono<AlgoStrategyTrade> placeLegStopLoss(
			RunningStrategyContext ctx,
			AlgoStrategyTrade trade) {
		if (trade.slTriggerPrice() == null) {
			return Mono.just(trade);
		}
		String exitSide = "BUY".equalsIgnoreCase(trade.side()) ? "SELL" : "BUY";
		String tag = "algo-sl-" + ctx.strategy().id().toString().substring(0, 8);
		PlaceOrderBody slOrder = new PlaceOrderBody(
				trade.instrumentKey(),
				exitSide,
				trade.quantity(),
				"I",
				"SL-M",
				"DAY",
				0.0,
				trade.slTriggerPrice(),
				tag,
				Boolean.TRUE);
		return orderService.place(ctx.strategy().userId(), slOrder)
				.flatMap(placed -> {
					String slId = firstOrderId(placed);
					if (slId == null || (placed.status() != null && "error".equalsIgnoreCase(placed.status()))) {
						return algoStrategyService.logEvent(
										ctx.strategy().id(),
										ctx.run().id(),
										ctx.strategy().userId(),
										"SL_PLACE_FAILED",
										"Broker SL-M failed for " + trade.tradingSymbol()
												+ "; software SL active at " + trade.slTriggerPrice())
								.thenReturn(trade);
					}
					AlgoStrategyTrade withSl = trade.withSl(slId, trade.slTriggerPrice());
					return algoStrategyService.updateTrade(withSl)
							.then(algoStrategyService.logEvent(
									ctx.strategy().id(),
									ctx.run().id(),
									ctx.strategy().userId(),
									"SL_PLACED",
									"SL-M " + slId + " @ " + trade.slTriggerPrice()
											+ " for " + trade.tradingSymbol()))
							.thenReturn(withSl);
				})
				.onErrorResume(err -> algoStrategyService.logEvent(
								ctx.strategy().id(),
								ctx.run().id(),
								ctx.strategy().userId(),
								"SL_PLACE_FAILED",
								err.getMessage() != null ? err.getMessage() : err.toString())
						.thenReturn(trade));
	}

	private static String firstOrderId(PlaceOrderResult placed) {
		if (placed == null || placed.orderIds() == null || placed.orderIds().isEmpty()) {
			return null;
		}
		return placed.orderIds().getFirst();
	}

	private Mono<Void> manageActive(RunningStrategyContext ctx, LocalTime now) {
		EntrySettings entry = ctx.config().entry();
		LocalTime exitTime = parseTime(entry.exitTime());
		if (exitTime != null && !now.isBefore(exitTime)) {
			return algoStrategyService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "EXIT_TIME")
					.then();
		}
		return algoStrategyService.loadOpenTrades(ctx.run().id())
				.flatMap(open -> {
					if (open.isEmpty()) {
						return algoStrategyService.exitRun(
								ctx.strategy(), ctx.run(), ctx.strategy().userId(), "NO_OPEN_LEGS").then();
					}
					return reconcileBrokerFlats(ctx, open)
							.flatMap(stillOpen -> {
								if (stillOpen.isEmpty()) {
									return algoStrategyService.exitRun(
											ctx.strategy(), ctx.run(), ctx.strategy().userId(), "NO_OPEN_LEGS").then();
								}
								Mono<Void> afterPartial = stillOpen.size() < open.size()
										? algoStrategyService.applyOverallBudgetAfterPartialExit(
												ctx.strategy(),
												ctx.run(),
												ctx.strategy().userId(),
												ctx.config(),
												stillOpen)
										: Mono.empty();
								return afterPartial.then(mtmAndMaybeExit(ctx, stillOpen));
							});
				});
	}

	/** Mark legs flat at broker (e.g. SL-M triggered) as reconciled. */
	private Mono<List<AlgoStrategyTrade>> reconcileBrokerFlats(
			RunningStrategyContext ctx,
			List<AlgoStrategyTrade> open) {
		if (!"LIVE".equalsIgnoreCase(ctx.run().executionMode())) {
			return Mono.just(open);
		}
		return algoStrategyService.reconcileFlatLegs(ctx.strategy(), ctx.run(), ctx.strategy().userId(), open);
	}

	private Mono<Void> mtmAndMaybeExit(RunningStrategyContext ctx, List<AlgoStrategyTrade> open) {
		List<String> keys = open.stream().map(AlgoStrategyTrade::instrumentKey).distinct().toList();
		return algoStrategyService.loadClosedTrades(ctx.run().id())
				.flatMap(closed -> quoteService.ltp(ctx.strategy().userId(), keys)
						.map(res -> res.prices())
						.onErrorReturn(Map.of())
						.flatMap(prices -> {
							double unrealised = 0.0;
							for (AlgoStrategyTrade t : open) {
								double ltp = prices.getOrDefault(t.instrumentKey(), t.entryPrice());
								unrealised += AlgoStrategyService.computePnl(
										t.side(), t.entryPrice(), ltp, t.quantity());
							}
							unrealised = Math.round(unrealised * 100.0) / 100.0;
							double premiumPts = AlgoOverallMath.combinedPremiumPointPnl(closed, open, prices);
							double peak = Math.max(ctx.run().peakPnl(), unrealised);
							Double trailFloor = updateTrailFloor(
									ctx.run().trailFloorPnl(), peak, ctx.config().overall());
							String overallExit = evaluateOverallExit(
									unrealised, premiumPts, peak, trailFloor, ctx.config().overall());
							Mono<AlgoStrategyRun> mtm = algoStrategyService.updateRunMtm(
									ctx.run(), unrealised, peak, trailFloor);

							if (overallExit != null) {
								return mtm.flatMap(updatedRun -> algoStrategyService.exitRun(
										ctx.strategy(), updatedRun, ctx.strategy().userId(), overallExit)).then();
							}

							// PAPER (or LIVE fallback): per-leg software SL
							List<AlgoStrategyTrade> breached = new ArrayList<>();
							for (AlgoStrategyTrade t : open) {
								if (t.slTriggerPrice() == null) {
									continue;
								}
								// LIVE with resting broker SL-M: let broker handle unless SL place failed
								if ("LIVE".equalsIgnoreCase(t.executionMode())
										&& t.slOrderId() != null
										&& !t.slOrderId().isBlank()) {
									continue;
								}
								double ltp = prices.getOrDefault(t.instrumentKey(), t.entryPrice());
								if (AlgoSlMath.softwareSlBreached(t.side(), ltp, t.slTriggerPrice())) {
									breached.add(t);
								}
							}
							if (!breached.isEmpty()) {
								return mtm.flatMap(updatedRun -> algoStrategyService.exitSelectedLegs(
										ctx.strategy(),
										updatedRun,
										ctx.strategy().userId(),
										breached,
										"LEG_SL")).then();
							}
							return mtm.then();
						}));
	}

	private static Double updateTrailFloor(Double currentFloor, double peak, OverallSettings overall) {
		if (overall == null || !overall.trailingEnabled()) {
			return currentFloor;
		}
		Double profitReaches = overall.profitReaches();
		Double lockProfit = overall.lockProfit();
		if (profitReaches == null || lockProfit == null) {
			return currentFloor;
		}
		if (peak < profitReaches) {
			return currentFloor;
		}
		double floor = lockProfit;
		Double stepProfit = overall.trailStepProfit();
		Double stepLock = overall.trailStepLock();
		if (stepProfit != null && stepProfit > 0 && stepLock != null) {
			double extra = peak - profitReaches;
			int steps = (int) Math.floor(extra / stepProfit);
			floor = lockProfit + steps * stepLock;
		}
		if (currentFloor == null || floor > currentFloor) {
			return floor;
		}
		return currentFloor;
	}

	/**
	 * @param rupeeUnrealised open-leg ₹ MTM (for trailing lock)
	 * @param premiumPointPnl closed+open premium-point PnL (qty-independent; for Points/Premium SL & target)
	 */
	static String evaluateOverallExit(
			double rupeeUnrealised,
			double premiumPointPnl,
			double peak,
			Double trailFloor,
			OverallSettings overall) {
		if (overall == null) {
			return null;
		}
		if (overall.overallSlEnabled() && overall.overallSlValue() != null) {
			double metric = AlgoOverallMath.isPointsOrPremium(overall.overallSlType())
					? premiumPointPnl
					: rupeeUnrealised;
			double sl = -Math.abs(overall.overallSlValue());
			if (metric <= sl) {
				return "OVERALL_SL";
			}
		}
		if (overall.overallTargetEnabled() && overall.overallTargetValue() != null) {
			double metric = AlgoOverallMath.isPointsOrPremium(overall.overallTargetType())
					? premiumPointPnl
					: rupeeUnrealised;
			if (metric >= overall.overallTargetValue()) {
				return "OVERALL_TARGET";
			}
		}
		if (overall.trailingEnabled() && trailFloor != null && rupeeUnrealised <= trailFloor) {
			return "TRAILING_LOCK";
		}
		return null;
	}

	private static ResolvedInstrument pickByPremiumResolved(
			OptionChainResponse chain,
			boolean call,
			double targetPremium) {
		OptionSide best = null;
		Double bestStrike = null;
		double bestDiff = Double.MAX_VALUE;
		for (OptionStrikeRow row : chain.strikes()) {
			OptionSide side = call ? row.call() : row.put();
			if (side == null || side.ltp() == null) {
				continue;
			}
			double diff = Math.abs(side.ltp() - targetPremium);
			if (diff < bestDiff) {
				bestDiff = diff;
				best = side;
				bestStrike = row.strike();
			}
		}
		if (best == null) {
			return null;
		}
		return new ResolvedInstrument(
				best.instrumentKey(), best.tradingSymbol(), best.ltp(), best.lotSize(), bestStrike);
	}

	private static String resolveUnderlyingKey(String index) {
		return InstrumentsProperties.underlyingKeyForSymbol(index);
	}

	private Mono<String> resolveExpiryDate(UUID userId, String index, List<LegConfig> legs) {
		String expiry = (legs == null || legs.isEmpty()) ? null : legs.getFirst().expiry();
		if (expiry != null && !expiry.isBlank()
				&& !List.of("WEEKLY", "CURRENT", "NEAREST", "MONTHLY", "NEXT WEEKLY")
						.contains(expiry.trim().toUpperCase(Locale.ROOT))) {
			return Mono.just(expiry.trim());
		}
		boolean monthly = expiry != null && "MONTHLY".equalsIgnoreCase(expiry.trim());
		boolean nextWeekly = expiry != null && expiry.trim().toUpperCase(Locale.ROOT).contains("NEXT");
		String under = index != null && !index.isBlank() ? index : "NIFTY";
		return optionChainService.listNiftyExpiries(userId)
				.flatMap(dates -> {
					Optional<String> picked = pickExpiry(dates, monthly, nextWeekly);
					if (picked.isPresent()) {
						return Mono.just(picked.get());
					}
					return scriptMasterExpiry(userId, under, monthly, nextWeekly);
				})
				.onErrorResume(err -> {
					log.warn("Option-chain expiry list failed; using script master: {}", err.toString());
					return scriptMasterExpiry(userId, under, monthly, nextWeekly);
				})
				.switchIfEmpty(Mono.error(new IllegalStateException(
						"No option expiry available for " + under
								+ " (option-chain master empty and script master has no CE/PE expiries)")));
	}

	private Mono<String> scriptMasterExpiry(UUID userId, String under, boolean monthly, boolean nextWeekly) {
		return instrumentService.ensureLoaded(userId).flatMap(ignored -> {
			List<LocalDate> dates = instrumentService.expiries(under);
			if (dates.isEmpty() && MstockInstrumentService.normalizeUnderlyingSymbol(under).equals("NIFTY")) {
				dates = instrumentService.expiries("NSE:NIFTY50");
			}
			Optional<String> picked = pickExpiry(dates, monthly, nextWeekly);
			return picked.map(Mono::just).orElseGet(Mono::empty);
		});
	}

	/**
	 * Pick weekly/monthly expiry from listed dates. Empty list → empty (never invent a Thursday).
	 */
	static Optional<String> pickExpiry(List<LocalDate> dates, boolean monthly, boolean nextWeekly) {
		return pickExpiry(dates, monthly, nextWeekly, LocalDate.now(IST));
	}

	static Optional<String> pickExpiry(
			List<LocalDate> dates, boolean monthly, boolean nextWeekly, LocalDate today) {
		if (dates == null || dates.isEmpty()) {
			return Optional.empty();
		}
		List<LocalDate> future = dates.stream().filter(d -> !d.isBefore(today)).toList();
		List<LocalDate> use = future.isEmpty() ? dates : future;
		if (monthly) {
			return Optional.of(use.getLast().toString());
		}
		if (nextWeekly && use.size() > 1) {
			return Optional.of(use.get(1).toString());
		}
		return Optional.of(use.getFirst().toString());
	}

	/**
	 * Reject script-master / catalog symbols whose underlying does not match the strategy index.
	 * Synthetic chain symbols like {@code CE-25550} are allowed (chain is already NIFTY-scoped).
	 */
	static void assertUnderlyingMatch(String configuredIndex, String instrumentKey, String tradingSymbol) {
		if (tradingSymbol == null || tradingSymbol.isBlank()) {
			return;
		}
		String sym = tradingSymbol.trim().toUpperCase(Locale.ROOT);
		if (sym.matches("^(CE|PE)-\\d+(\\.\\d+)?$")) {
			return;
		}
		String want = MstockInstrumentService.normalizeUnderlyingSymbol(configuredIndex);
		if (want.isBlank()) {
			return;
		}
		if (!MstockInstrumentService.matchesUnderlying(want, null, sym)
				&& !MstockInstrumentService.matchesUnderlying(want, inferUnderlyingFromSymbol(sym), sym)) {
			throw new IllegalStateException(
					"Resolved instrument underlying mismatch: want=" + want
							+ " got tradingSymbol=" + tradingSymbol
							+ " key=" + instrumentKey);
		}
	}

	/** Best-effort underlying from F&O trading symbol (NIFTY… / FINNIFTY… / BANKNIFTY…). */
	static String inferUnderlyingFromSymbol(String tradingSymbol) {
		if (tradingSymbol == null || tradingSymbol.isBlank()) {
			return null;
		}
		String sym = tradingSymbol.trim().toUpperCase(Locale.ROOT);
		if (sym.startsWith("BANKNIFTY")) {
			return "BANKNIFTY";
		}
		if (sym.startsWith("FINNIFTY")) {
			return "FINNIFTY";
		}
		if (sym.startsWith("MIDCPNIFTY")) {
			return "MIDCPNIFTY";
		}
		if (sym.startsWith("NIFTY")) {
			return "NIFTY";
		}
		return null;
	}

	private static LocalTime parseTime(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			String t = raw.trim();
			if (t.length() == 4 && t.charAt(1) == ':') {
				t = "0" + t;
			}
			if (t.length() == 5) {
				return LocalTime.parse(t);
			}
			return LocalTime.parse(t);
		}
		catch (Exception ex) {
			return null;
		}
	}

}

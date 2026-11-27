package com.sgalgodesk.desk.application.dds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockOptionChainService;
import com.sgalgodesk.desk.application.algo.AlgoLivePriceLease;
import com.sgalgodesk.desk.application.algo.AlgoStrikeResolve;
import com.sgalgodesk.desk.application.dds.DdsDeltaResolve.Candidate;
import com.sgalgodesk.desk.application.dds.DdsDtos.DdsSettings;
import com.sgalgodesk.desk.application.dds.DdsStrategyService.ResolvedLeg;
import com.sgalgodesk.desk.application.dds.DdsStrategyService.RunningContext;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.quote.QuoteService;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class DdsStrategyEngine {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final DdsStrategyService ddsService;
	private final MstockOptionChainService optionChainService;
	private final MstockMarketCalendarService marketCalendar;
	private final QuoteService quoteService;
	private final OrderService orderService;
	private final AlgoLivePriceLease algoLivePriceLease;

	private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, Boolean> adjusting = new ConcurrentHashMap<>();

	@Scheduled(fixedDelayString = "500", initialDelayString = "7000")
	public void tick() {
		ddsService.listRunningStrategies()
				.concatMap(this::processOne)
				.onErrorContinue((err, o) -> log.warn("DDS tick error: {}", err.toString()))
				.subscribe();
	}

	private Mono<Void> processOne(RunningContext ctx) {
		UUID id = ctx.strategy().id();
		if (inFlight.putIfAbsent(id, Boolean.TRUE) != null) {
			return Mono.empty();
		}
		Set<String> keys = ctx.openTrades().stream().map(DdsTrade::instrumentKey).collect(Collectors.toSet());
		keys.add(DdsStrategyService.NIFTY_CMP_KEY);
		return algoLivePriceLease.ensure(ctx.strategy().userId(), keys)
				.then(Mono.defer(() -> doProcess(ctx)))
				.doFinally(sig -> inFlight.remove(id));
	}

	private Mono<Void> doProcess(RunningContext ctx) {
		if (!marketCalendar.isSessionOpenNow()) {
			return Mono.empty();
		}
		String state = ctx.run().lifecycleState();
		if ("WAITING_ENTRY".equals(state) || "ENTERING".equals(state)) {
			return maybeEnter(ctx);
		}
		if ("ACTIVE".equals(state)) {
			return manageActive(ctx);
		}
		return Mono.empty();
	}

	private Mono<Void> maybeEnter(RunningContext ctx) {
		LocalDate today = LocalDate.now(IST);
		LocalDate runDate = ctx.run().runDate();
		if (runDate != null && runDate.isAfter(today)) {
			return Mono.empty();
		}
		LocalTime now = LocalTime.now(IST);
		if (now.isBefore(DdsStrategyService.ENTRY_TIME)) {
			return Mono.empty();
		}
		LocalTime exitTime = DdsStrategyService.parseTime(ctx.settings().exitTime());
		if (exitTime != null && !now.isBefore(exitTime)) {
			return ddsService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "TIME_EXIT").then();
		}
		List<DdsTrade> open = ctx.openTrades();
		boolean hasCe = open.stream().anyMatch(t -> "CE".equalsIgnoreCase(t.legRole()) || "CE".equalsIgnoreCase(t.optionType()));
		boolean hasPe = open.stream().anyMatch(t -> "PE".equalsIgnoreCase(t.legRole()) || "PE".equalsIgnoreCase(t.optionType()));
		if (hasCe && hasPe) {
			return ddsService.markRunActive(ctx.strategy(), ctx.run());
		}
		return resolveAndEnter(ctx);
	}

	private Mono<Void> resolveAndEnter(RunningContext ctx) {
		UUID userId = ctx.strategy().userId();
		Mono<Boolean> claim = "ENTERING".equals(ctx.run().lifecycleState())
				? Mono.just(true)
				: ddsService.tryClaimEntry(ctx.run());
		return claim.flatMap(claimed -> {
			if (!Boolean.TRUE.equals(claimed)) {
				return Mono.empty();
			}
			return optionChainService.listNiftyExpiries(userId)
					.flatMap(expiries -> {
						LocalDate today = LocalDate.now(IST);
						LocalDate expiry = DdsExpirySelect.select(today, expiries);
						String expiryStr = expiry.toString();
						return optionChainService.getChain(userId, DdsStrategyService.NIFTY_CMP_KEY, expiryStr)
								.flatMap(chain -> resolveSpot(userId, chain).flatMap(spot -> {
									List<String> keys = collectInstrumentKeys(chain);
									return quoteService.ltp(userId, keys).flatMap(ltpRes -> {
										Map<String, Double> prices = ltpRes.prices() != null
												? ltpRes.prices()
												: Map.of();
										double target = ctx.settings().initialDelta();
										Candidate ce = DdsDeltaResolve.pick(
												chain, "CE", spot, today, expiry, target, prices);
										Candidate pe = DdsDeltaResolve.pick(
												chain, "PE", spot, today, expiry, target, prices);
										int lotSize = AlgoStrikeResolve.resolveLotSize(
												null, null, chain.lotSize(), null);
										int qty = lotSize * Math.max(1, ctx.settings().lots());
										List<ResolvedLeg> legs = List.of(
												toResolved(ce, qty, lotSize, expiryStr),
												toResolved(pe, qty, lotSize, expiryStr));
										if ("LIVE".equalsIgnoreCase(ctx.run().executionMode())) {
											return enterLive(ctx, legs);
										}
										return enterPaper(ctx, legs, prices);
									});
								}));
					})
					.onErrorResume(err -> ddsService.releaseEntryClaimIfIdle(ctx.run())
							.then(ddsService.logEvent(
									ctx.strategy().id(),
									ctx.run().id(),
									userId,
									"ENTRY_ERROR",
									err.getMessage() != null ? err.getMessage() : err.toString()))
							.then());
		});
	}

	private ResolvedLeg toResolved(Candidate c, int qty, int lotSize, String expiry) {
		double entry = c.ltp() != null && c.ltp() > 0 ? c.ltp() : 0;
		return new ResolvedLeg(
				c.optionType(),
				c.instrumentKey(),
				c.tradingSymbol(),
				"SELL",
				qty,
				entry,
				null,
				c.signedDelta(),
				lotSize,
				c.strike(),
				expiry,
				c.optionType());
	}

	private Mono<Void> enterPaper(RunningContext ctx, List<ResolvedLeg> legs, Map<String, Double> prices) {
		List<ResolvedLeg> withPx = legs.stream().map(leg -> {
			double px = prices.getOrDefault(leg.instrumentKey(), leg.entryPrice());
			if (px <= 0) {
				px = leg.entryPrice();
			}
			return new ResolvedLeg(
					leg.legRole(),
					leg.instrumentKey(),
					leg.tradingSymbol(),
					leg.side(),
					leg.quantity(),
					px,
					leg.brokerOrderId(),
					leg.deltaAtEntry(),
					leg.lotSize(),
					leg.strike(),
					leg.expiry(),
					leg.optionType());
		}).toList();
		return ddsService.enterLegs(ctx.strategy(), ctx.run(), withPx, "PAPER");
	}

	private Mono<Void> enterLive(RunningContext ctx, List<ResolvedLeg> legs) {
		UUID userId = ctx.strategy().userId();
		String tag = "dds-" + ctx.strategy().id().toString().substring(0, 8);
		List<ResolvedLeg> filled = new ArrayList<>();
		return reactor.core.publisher.Flux.fromIterable(legs)
				.concatMap(leg -> {
					PlaceOrderBody body = new PlaceOrderBody(
							leg.instrumentKey(),
							"SELL",
							leg.quantity(),
							"I",
							"MARKET",
							"DAY",
							0.0,
							null,
							tag,
							Boolean.TRUE);
					return orderService.place(userId, body).flatMap(placed -> {
						String orderId = placed.orderIds() != null && !placed.orderIds().isEmpty()
								? placed.orderIds().getFirst()
								: null;
						if (orderId == null) {
							return Mono.error(new IllegalStateException("LIVE entry failed for " + leg.tradingSymbol()));
						}
						return orderService.waitUntilComplete(userId, orderId)
								.map(fill -> {
									double px = fill.averagePrice() > 0 ? fill.averagePrice() : leg.entryPrice();
									ResolvedLeg done = new ResolvedLeg(
											leg.legRole(),
											leg.instrumentKey(),
											leg.tradingSymbol(),
											leg.side(),
											leg.quantity(),
											px,
											orderId,
											leg.deltaAtEntry(),
											leg.lotSize(),
											leg.strike(),
											leg.expiry(),
											leg.optionType());
									filled.add(done);
									return done;
								});
					});
				})
				.then(Mono.defer(() -> ddsService.enterLegs(ctx.strategy(), ctx.run(), filled, "LIVE")))
				.onErrorResume(err -> {
					if (filled.isEmpty()) {
						return ddsService.releaseEntryClaimIfIdle(ctx.run())
								.then(ddsService.logEvent(
										ctx.strategy().id(),
										ctx.run().id(),
										userId,
										"ENTRY_ERROR",
										err.getMessage() != null ? err.getMessage() : err.toString()));
					}
					// Persist what filled, then unwind
					return ddsService.enterLegs(ctx.strategy(), ctx.run(), filled, "LIVE")
							.then(ddsService.exitRun(ctx.strategy(), ctx.run(), userId, "ENTRY_UNWIND"))
							.then();
				});
	}

	private Mono<Void> manageActive(RunningContext ctx) {
		LocalTime now = LocalTime.now(IST);
		LocalTime exitTime = DdsStrategyService.parseTime(ctx.settings().exitTime());
		if (exitTime != null && !now.isBefore(exitTime)) {
			return ddsService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "TIME_EXIT").then();
		}
		List<DdsTrade> open = ctx.openTrades();
		DdsTrade ce = findOpen(open, "CE");
		DdsTrade pe = findOpen(open, "PE");
		if (ce == null || pe == null) {
			return ddsService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), "NO_OPEN_PAIR").then();
		}
		UUID userId = ctx.strategy().userId();
		List<String> keys = List.of(ce.instrumentKey(), pe.instrumentKey(), DdsStrategyService.NIFTY_CMP_KEY);
		return quoteService.ltp(userId, keys).flatMap(ltpRes -> {
			Map<String, Double> prices = ltpRes.prices() != null ? new HashMap<>(ltpRes.prices()) : new HashMap<>();
			double cePx = prices.getOrDefault(ce.instrumentKey(), 0.0);
			double pePx = prices.getOrDefault(pe.instrumentKey(), 0.0);
			if (cePx <= 0 || pePx <= 0) {
				return Mono.empty();
			}
			double unrealised = DdsStrategyService.computePnl(ce.side(), ce.entryPrice(), cePx, ce.quantity())
					+ DdsStrategyService.computePnl(pe.side(), pe.entryPrice(), pePx, pe.quantity());
			double dayMoney = ctx.run().realisedPnl() + unrealised;
			double dayPoints = ctx.run().realisedPnl() == 0 && unrealised == 0
					? 0
					: pointPnlOpenAndClosed(ctx, ce, pe, cePx, pePx);

			return ddsService.updateRunMtm(ctx.run(), unrealised).then(Mono.defer(() -> {
				if (DdsStrategyService.maxLossHit(ctx.settings(), dayMoney, dayPoints)) {
					return ddsService.exitRun(ctx.strategy(), ctx.run(), userId, "MAX_LOSS").then();
				}
				boolean doubleHit = cePx >= 2 * pePx || pePx >= 2 * cePx;
				if (!doubleHit) {
					return Mono.empty();
				}
				DdsTrade loser = cePx >= 2 * pePx ? ce : pe;
				DdsTrade profit = loser == ce ? pe : ce;
				double spot = prices.getOrDefault(DdsStrategyService.NIFTY_CMP_KEY, 0.0);
				if (spot <= 0) {
					return resolveSpot(userId, null).flatMap(s -> handleDouble(ctx, loser, profit, s));
				}
				return handleDouble(ctx, loser, profit, spot);
			}));
		});
	}

	private double pointPnlOpenAndClosed(
			RunningContext ctx, DdsTrade ce, DdsTrade pe, double cePx, double pePx) {
		// Approximate day points from open marks + realised money converted roughly via lot
		double openPts = DdsStrategyService.premiumPointPnl(ce, cePx)
				+ DdsStrategyService.premiumPointPnl(pe, pePx);
		int lot = ce.lotSize() != null && ce.lotSize() > 0 ? ce.lotSize() : 65;
		double realisedPts = lot > 0 ? ctx.run().realisedPnl() / lot : ctx.run().realisedPnl();
		return openPts + realisedPts;
	}

	private Mono<Void> handleDouble(RunningContext ctx, DdsTrade loser, DdsTrade profit, double spot) {
		UUID strategyId = ctx.strategy().id();
		if (adjusting.putIfAbsent(strategyId, Boolean.TRUE) != null) {
			return Mono.empty();
		}
		Double strike = loser.strike();
		String opt = loser.optionType() != null ? loser.optionType() : loser.legRole();
		if (strike != null && DdsDeltaResolve.isItm(opt, strike, spot)) {
			String reason = "CE".equalsIgnoreCase(opt) ? "CE_ITM_EXIT" : "PE_ITM_EXIT";
			return ddsService.exitRun(ctx.strategy(), ctx.run(), ctx.strategy().userId(), reason)
					.doFinally(s -> adjusting.remove(strategyId))
					.then();
		}
		return adjustOptionB(ctx, loser, profit, spot)
				.doFinally(s -> adjusting.remove(strategyId));
	}

	/** Close profit leg; sell new opposite at |loserΔ|−5. */
	private Mono<Void> adjustOptionB(RunningContext ctx, DdsTrade loser, DdsTrade profit, double spot) {
		UUID userId = ctx.strategy().userId();
		double loserAbsDelta = loser.deltaAtEntry() != null
				? Math.abs(loser.deltaAtEntry())
				: ctx.settings().initialDelta() / 100.0;
		if (loserAbsDelta > 1) {
			loserAbsDelta = loserAbsDelta / 100.0;
		}
		double targetAbs = Math.max(0.01, loserAbsDelta - 0.05);
		// target for pick() accepts percent or fraction
		double targetForPick = targetAbs * 100.0;
		String newSide = "CE".equalsIgnoreCase(profit.optionType() != null ? profit.optionType() : profit.legRole())
				? "CE"
				: "PE";
		// New opposite = same type as closed profit side
		String expiry = loser.expiry() != null ? loser.expiry() : profit.expiry();
		if (expiry == null) {
			return ddsService.logEvent(ctx.strategy().id(), ctx.run().id(), userId, "ADJUST_ERROR", "Missing expiry")
					.then();
		}
		LocalDate expiryDate = LocalDate.parse(expiry);
		LocalDate today = LocalDate.now(IST);
		return ddsService.closeLeg(ctx.strategy(), ctx.run(), profit, "PREMIUM_DOUBLE_BOOK")
				.then(optionChainService.getChain(userId, DdsStrategyService.NIFTY_CMP_KEY, expiry)
						.flatMap(chain -> {
							List<String> keys = collectInstrumentKeys(chain);
							return quoteService.ltp(userId, keys).flatMap(ltpRes -> {
								Map<String, Double> prices = ltpRes.prices() != null ? ltpRes.prices() : Map.of();
								Candidate pick = DdsDeltaResolve.pick(
										chain, newSide, spot, today, expiryDate, targetForPick, prices);
								int lotSize = AlgoStrikeResolve.resolveLotSize(
										null, null, chain.lotSize(), profit.lotSize());
								int qty = lotSize * Math.max(1, ctx.settings().lots());
								ResolvedLeg leg = toResolved(pick, qty, lotSize, expiry);
								if ("LIVE".equalsIgnoreCase(ctx.run().executionMode())) {
									return enterLiveSingle(ctx, leg);
								}
								double px = prices.getOrDefault(leg.instrumentKey(),
										leg.entryPrice() > 0 ? leg.entryPrice() : 0);
								ResolvedLeg withPx = new ResolvedLeg(
										leg.legRole(),
										leg.instrumentKey(),
										leg.tradingSymbol(),
										leg.side(),
										leg.quantity(),
										px > 0 ? px : leg.entryPrice(),
										null,
										leg.deltaAtEntry(),
										leg.lotSize(),
										leg.strike(),
										leg.expiry(),
										leg.optionType());
								Instant now = Instant.now();
								DdsTrade trade = new DdsTrade(
										UUID.randomUUID(),
										ctx.strategy().id(),
										ctx.run().id(),
										userId,
										withPx.legRole(),
										withPx.instrumentKey(),
										withPx.tradingSymbol(),
										withPx.side(),
										withPx.quantity(),
										"PAPER",
										null,
										withPx.entryPrice(),
										null,
										0,
										"PREMIUM_DOUBLE_ADJUST",
										"OPEN",
										withPx.deltaAtEntry(),
										withPx.lotSize(),
										withPx.strike(),
										withPx.expiry(),
										withPx.optionType(),
										now,
										null);
								return ddsService.insertOpenTrade(trade)
										.then(ddsService.logEvent(
												ctx.strategy().id(),
												ctx.run().id(),
												userId,
												"PREMIUM_DOUBLE_ADJUST",
												"Booked " + profit.legRole() + "; sold " + newSide
														+ " @ Δ~" + String.format("%.2f", targetAbs)));
							});
						}));
	}

	private Mono<Void> enterLiveSingle(RunningContext ctx, ResolvedLeg leg) {
		UUID userId = ctx.strategy().userId();
		String tag = "dds-" + ctx.strategy().id().toString().substring(0, 8);
		PlaceOrderBody body = new PlaceOrderBody(
				leg.instrumentKey(), "SELL", leg.quantity(), "I", "MARKET", "DAY", 0.0, null, tag, Boolean.TRUE);
		return orderService.place(userId, body).flatMap(placed -> {
			String orderId = placed.orderIds() != null && !placed.orderIds().isEmpty()
					? placed.orderIds().getFirst()
					: null;
			Mono<Double> pxMono = orderId == null
					? Mono.just(leg.entryPrice())
					: orderService.waitUntilComplete(userId, orderId)
							.map(f -> f.averagePrice() > 0 ? f.averagePrice() : leg.entryPrice())
							.onErrorReturn(leg.entryPrice());
			return pxMono.flatMap(px -> {
				Instant now = Instant.now();
				DdsTrade trade = new DdsTrade(
						UUID.randomUUID(),
						ctx.strategy().id(),
						ctx.run().id(),
						userId,
						leg.legRole(),
						leg.instrumentKey(),
						leg.tradingSymbol(),
						leg.side(),
						leg.quantity(),
						"LIVE",
						orderId,
						px,
						null,
						0,
						"PREMIUM_DOUBLE_ADJUST",
						"OPEN",
						leg.deltaAtEntry(),
						leg.lotSize(),
						leg.strike(),
						leg.expiry(),
						leg.optionType(),
						now,
						null);
				return ddsService.insertOpenTrade(trade)
						.then(ddsService.logEvent(
								ctx.strategy().id(),
								ctx.run().id(),
								userId,
								"PREMIUM_DOUBLE_ADJUST",
								"Adjustment sold " + leg.legRole() + " " + leg.tradingSymbol()));
			});
		});
	}

	private static DdsTrade findOpen(List<DdsTrade> open, String role) {
		if (open == null) {
			return null;
		}
		return open.stream()
				.filter(t -> role.equalsIgnoreCase(t.legRole()) || role.equalsIgnoreCase(t.optionType()))
				.findFirst()
				.orElse(null);
	}

	private Mono<Double> resolveSpot(UUID userId, OptionChainResponse chain) {
		if (chain != null && chain.underlyingSpot() != null && chain.underlyingSpot() > 0) {
			return Mono.just(chain.underlyingSpot());
		}
		return quoteService.ltp(userId, List.of(DdsStrategyService.NIFTY_CMP_KEY))
				.map(r -> r.prices().getOrDefault(DdsStrategyService.NIFTY_CMP_KEY, 0.0))
				.flatMap(cmp -> cmp > 0
						? Mono.just(cmp)
						: Mono.error(new IllegalStateException("Could not fetch NIFTY spot")));
	}

	private static List<String> collectInstrumentKeys(OptionChainResponse chain) {
		List<String> keys = new ArrayList<>();
		if (chain == null || chain.strikes() == null) {
			return keys;
		}
		for (OptionStrikeRow row : chain.strikes()) {
			if (row.call() != null && row.call().instrumentKey() != null) {
				keys.add(row.call().instrumentKey());
			}
			if (row.put() != null && row.put().instrumentKey() != null) {
				keys.add(row.put().instrumentKey());
			}
		}
		return keys;
	}
}

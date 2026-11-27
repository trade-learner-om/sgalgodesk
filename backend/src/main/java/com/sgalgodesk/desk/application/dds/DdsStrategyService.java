package com.sgalgodesk.desk.application.dds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.application.algo.AlgoLivePriceLease;
import com.sgalgodesk.desk.application.dds.DdsDtos.DdsSettings;
import com.sgalgodesk.desk.application.dds.DdsDtos.EventDto;
import com.sgalgodesk.desk.application.dds.DdsDtos.HistoryResponse;
import com.sgalgodesk.desk.application.dds.DdsDtos.RunDto;
import com.sgalgodesk.desk.application.dds.DdsDtos.RunDdsStrategyRequest;
import com.sgalgodesk.desk.application.dds.DdsDtos.SaveDdsStrategyRequest;
import com.sgalgodesk.desk.application.dds.DdsDtos.StrategyLifecycleResponse;
import com.sgalgodesk.desk.application.dds.DdsDtos.StrategySummaryResponse;
import com.sgalgodesk.desk.application.dds.DdsDtos.TradeDto;
import com.sgalgodesk.desk.application.dds.port.DdsStrategyStorePort;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.positions.PositionBookService;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;
import com.sgalgodesk.desk.application.quote.QuoteService;
import com.sgalgodesk.desk.domain.dds.DdsEvent;
import com.sgalgodesk.desk.domain.dds.DdsRun;
import com.sgalgodesk.desk.domain.dds.DdsStrategy;
import com.sgalgodesk.desk.domain.dds.DdsTrade;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.Forbidden;
import com.sgalgodesk.desk.domain.shared.NotFound;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class DdsStrategyService {

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	static final LocalTime ENTRY_TIME = LocalTime.of(9, 45);
	static final String NIFTY_CMP_KEY = "NSE:NIFTY50";

	private final DdsStrategyStorePort store;
	private final PositionBookService positionBookService;
	private final OrderService orderService;
	private final QuoteService quoteService;
	private final AlgoLivePriceLease algoLivePriceLease;
	private final MstockMarketCalendarService marketCalendar;
	private final JsonMapper jsonMapper;

	public Mono<List<StrategySummaryResponse>> listStrategies(UUID userId) {
		return store.findStrategiesByUser(userId).concatMap(this::toSummary).collectList();
	}

	public Mono<StrategySummaryResponse> createStrategy(UUID userId, SaveDdsStrategyRequest req) {
		Instant now = Instant.now();
		DdsSettings settings = normalizeSettings(req.settings());
		DdsStrategy strategy = new DdsStrategy(
				UUID.randomUUID(),
				userId,
				req.name().trim(),
				"SAVED",
				normalizeMode(req.executionMode()),
				writeSettings(settings),
				now,
				now);
		return store.insertStrategy(strategy)
				.flatMap(saved -> logEvent(saved.id(), null, userId, "CREATED",
						"Delta Drift Strangle saved: " + saved.name()).thenReturn(saved))
				.flatMap(this::toSummary);
	}

	public Mono<StrategySummaryResponse> updateStrategy(UUID userId, UUID id, SaveDdsStrategyRequest req) {
		return requireOwned(id, userId).flatMap(existing -> {
			if ("RUNNING".equals(existing.status())) {
				return Mono.error(new Conflict("Stop the running strategy before editing"));
			}
			String mode = req.executionMode() != null && !req.executionMode().isBlank()
					? normalizeMode(req.executionMode())
					: existing.executionMode();
			DdsStrategy updated = existing.withDefinition(
					req.name().trim(), mode, writeSettings(normalizeSettings(req.settings())), Instant.now());
			return store.updateStrategy(updated)
					.flatMap(saved -> logEvent(saved.id(), null, userId, "UPDATED", "Settings updated")
							.thenReturn(saved))
					.flatMap(this::toSummary);
		});
	}

	public Mono<Void> deleteStrategy(UUID userId, UUID id) {
		return requireOwned(id, userId).flatMap(existing -> {
			if ("RUNNING".equals(existing.status())) {
				return Mono.error(new Conflict("Stop the running strategy before deleting"));
			}
			return store.deleteStrategy(existing.id());
		});
	}

	public Mono<StrategySummaryResponse> runStrategy(UUID userId, UUID id, RunDdsStrategyRequest req) {
		String mode = normalizeMode(req.executionMode());
		return requireOwned(id, userId).flatMap(existing -> {
			if ("RUNNING".equals(existing.status())) {
				return Mono.error(new Conflict("Strategy is already running"));
			}
			Instant now = Instant.now();
			DdsStrategy running = existing.withRunning(mode, now);
			DdsRun run = new DdsRun(
					UUID.randomUUID(),
					running.id(),
					userId,
					LocalDate.now(IST),
					mode,
					"WAITING_ENTRY",
					0,
					0,
					now,
					null);
			return store.updateStrategy(running)
					.then(store.insertRun(run))
					.flatMap(savedRun -> logEvent(running.id(), savedRun.id(), userId, "RUN_STARTED",
							"Started in " + mode + " mode").thenReturn(running))
					.flatMap(this::toSummary);
		});
	}

	public Mono<StrategySummaryResponse> stopStrategy(UUID userId, UUID id) {
		return requireOwned(id, userId).flatMap(entity -> {
			if (!"RUNNING".equals(entity.status())) {
				return Mono.error(new ValidationFailed("Strategy is not running"));
			}
			return store.findLatestRun(entity.id())
					.flatMap(run -> exitRun(entity, run, userId, "MANUAL_STOP"))
					.switchIfEmpty(Mono.defer(() -> finalizeStop(entity, null, userId, 0.0)));
		});
	}

	public Mono<StrategyLifecycleResponse> getLifecycle(UUID userId, UUID id) {
		return requireOwned(id, userId).flatMap(entity -> toSummary(entity).flatMap(summary -> Mono.zip(
				store.findRunsByStrategy(id).map(this::toRunDto).collectList(),
				store.findTradesByStrategy(id).map(this::toTradeDto).collectList(),
				store.findEventsByStrategy(id).map(this::toEventDto).collectList())
				.map(t -> new StrategyLifecycleResponse(summary, t.getT1(), t.getT2(), t.getT3()))));
	}

	public Mono<HistoryResponse> getHistory(
			UUID userId, UUID id, LocalDate from, LocalDate to, String cursor, int limit) {
		return requireOwned(id, userId).flatMap(entity -> {
			LocalDate fromDate = from != null ? from : LocalDate.now(IST).minusDays(90);
			LocalDate toDate = to != null ? to : LocalDate.now(IST);
			if (toDate.isBefore(fromDate)) {
				return Mono.error(new ValidationFailed("to must be on or after from"));
			}
			LocalDate cursorDay = null;
			if (cursor != null && !cursor.isBlank()) {
				try {
					cursorDay = LocalDate.parse(cursor.trim());
				}
				catch (Exception ex) {
					return Mono.error(new ValidationFailed("Invalid cursor; expected yyyy-MM-dd"));
				}
			}
			Instant fromInstant = fromDate.atStartOfDay(IST).toInstant();
			Instant toExclusive = toDate.plusDays(1).atStartOfDay(IST).toInstant();
			int pageLimit = limit > 0 ? Math.min(limit, 60) : 10;
			LocalDate cursorExclusive = cursorDay;
			return store.findTradesByStrategyAndExecutedAtRange(id, fromInstant, toExclusive)
					.collectList()
					.map(trades -> {
						var page = DdsHistoryGrouping.page(
								trades, fromDate, toDate, cursorExclusive, pageLimit, this::toTradeDto);
						return new HistoryResponse(page.groups(), page.nextCursor(), page.hasMore());
					});
		});
	}

	public Mono<StrategySummaryResponse> exitRun(
			DdsStrategy entity, DdsRun run, UUID userId, String triggerReason) {
		String reason = triggerReason != null && !triggerReason.isBlank() ? triggerReason : "MANUAL_STOP";
		return store.findTradesByRun(run.id())
				.filter(t -> "OPEN".equals(t.status()))
				.collectList()
				.flatMap(openTrades -> {
					if (openTrades.isEmpty()) {
						return logEvent(entity.id(), run.id(), userId, "STOPPED",
								"Stopped with no open trades (" + reason + ")")
								.then(finishExit(entity, run, userId, 0.0, reason));
					}
					if ("LIVE".equalsIgnoreCase(run.executionMode())) {
						return exitLiveAll(entity, run, userId, openTrades, reason);
					}
					return exitPaperAll(entity, run, userId, openTrades, reason);
				});
	}

	private Mono<StrategySummaryResponse> exitPaperAll(
			DdsStrategy entity, DdsRun run, UUID userId, List<DdsTrade> open, String reason) {
		List<String> keys = open.stream().map(DdsTrade::instrumentKey).distinct().toList();
		return quoteService.ltp(userId, keys).flatMap(ltpRes -> {
			Map<String, Double> prices = ltpRes != null && ltpRes.prices() != null ? ltpRes.prices() : Map.of();
			Instant now = Instant.now();
			List<DdsTrade> closed = new ArrayList<>();
			double delta = 0;
			for (DdsTrade t : open) {
				double exitPx = prices.getOrDefault(t.instrumentKey(), t.entryPrice());
				if (exitPx <= 0) {
					exitPx = t.entryPrice();
				}
				double pnl = computePnl(t.side(), t.entryPrice(), exitPx, t.quantity());
				delta += pnl;
				closed.add(t.closed("CLOSED", exitPx, pnl, reason, now));
			}
			double finalDelta = delta;
			return store.updateTrades(closed)
					.then(logEvent(entity.id(), run.id(), userId, "PAPER_EXIT",
							"Closed " + closed.size() + " leg(s) (" + reason + ")"))
					.then(finishExit(entity, run, userId, finalDelta, reason));
		});
	}

	private Mono<StrategySummaryResponse> exitLiveAll(
			DdsStrategy entity, DdsRun run, UUID userId, List<DdsTrade> open, String reason) {
		String tag = "dds-" + entity.id().toString().substring(0, 8);
		Instant now = Instant.now();
		return positionBookService.snapshot(userId)
				.onErrorReturn(emptyPositions())
				.flatMap(snapshot -> {
					Map<String, PositionItem> brokerOpen = indexOpenPositions(snapshot);
					List<DdsTrade> closed = new ArrayList<>();
					List<DdsTrade> failed = new ArrayList<>();
					return Flux.fromIterable(open)
							.concatMap(trade -> {
								PositionItem pos = brokerOpen.get(trade.instrumentKey());
								if (pos == null || pos.quantity() == 0) {
									DdsTrade reconciled = trade.closed(
											"RECONCILED_CLOSED", trade.exitPrice(), trade.pnl(), reason, now);
									closed.add(reconciled);
									return store.updateTrade(reconciled).then();
								}
								int qty = Math.min(trade.quantity(), Math.abs(pos.quantity()));
								String exitSide = "BUY".equalsIgnoreCase(trade.side()) ? "SELL" : "BUY";
								PlaceOrderBody body = new PlaceOrderBody(
										trade.instrumentKey(), exitSide, qty, "I", "MARKET", "DAY", 0.0, null, tag,
										Boolean.TRUE);
								return orderService.place(userId, body)
										.flatMap(placed -> {
											String orderId = placed.orderIds() != null && !placed.orderIds().isEmpty()
													? placed.orderIds().getFirst()
													: null;
											if (orderId == null) {
												failed.add(trade);
												return Mono.empty();
											}
											return orderService.waitUntilComplete(userId, orderId)
													.onErrorResume(e -> Mono.empty())
													.flatMap(fill -> {
														double exitPx = fill != null && fill.averagePrice() > 0
																? fill.averagePrice()
																: (pos.lastPrice() > 0 ? pos.lastPrice()
																		: trade.entryPrice());
														double pnl = computePnl(
																trade.side(), trade.entryPrice(), exitPx,
																trade.quantity());
														DdsTrade c = trade.closed(
																"CLOSED", exitPx, pnl, reason, now);
														closed.add(c);
														return store.updateTrade(c).then();
													})
													.switchIfEmpty(Mono.defer(() -> {
														double exitPx = pos.lastPrice() > 0
																? pos.lastPrice()
																: trade.entryPrice();
														double pnl = computePnl(
																trade.side(), trade.entryPrice(), exitPx,
																trade.quantity());
														DdsTrade c = trade.closed(
																"CLOSED", exitPx, pnl, reason, now);
														closed.add(c);
														return store.updateTrade(c).then();
													}));
										})
										.onErrorResume(err -> {
											failed.add(trade);
											return logEvent(entity.id(), run.id(), userId, "EXIT_FAILED",
													err.getMessage() != null ? err.getMessage() : err.toString());
										});
							})
							.then(Mono.defer(() -> {
								double delta = closed.stream().mapToDouble(DdsTrade::pnl).sum();
								if (!failed.isEmpty()) {
									return logEvent(entity.id(), run.id(), userId, "EXIT_INCOMPLETE",
											"Some legs remain open").then(toSummary(entity));
								}
								return finishExit(entity, run, userId, delta, reason);
							}));
				});
	}

	/** Close a single open leg (PAPER via LTP, LIVE via MARKET). Leaves run ACTIVE. */
	public Mono<DdsTrade> closeLeg(DdsStrategy entity, DdsRun run, DdsTrade open, String reason) {
		UUID userId = entity.userId();
		if ("LIVE".equalsIgnoreCase(run.executionMode())) {
			String tag = "dds-" + entity.id().toString().substring(0, 8);
			PlaceOrderBody body = new PlaceOrderBody(
					open.instrumentKey(),
					"BUY".equalsIgnoreCase(open.side()) ? "SELL" : "BUY",
					open.quantity(),
					"I",
					"MARKET",
					"DAY",
					0.0,
					null,
					tag,
					Boolean.TRUE);
			Instant now = Instant.now();
			return orderService.place(userId, body).flatMap(placed -> {
				String orderId = placed.orderIds() != null && !placed.orderIds().isEmpty()
						? placed.orderIds().getFirst()
						: null;
				Mono<Double> pxMono = orderId == null
						? quoteService.ltp(userId, List.of(open.instrumentKey()))
								.map(r -> r.prices().getOrDefault(open.instrumentKey(), open.entryPrice()))
						: orderService.waitUntilComplete(userId, orderId)
								.map(f -> f.averagePrice() > 0 ? f.averagePrice() : open.entryPrice())
								.onErrorResume(e -> quoteService.ltp(userId, List.of(open.instrumentKey()))
										.map(r -> r.prices().getOrDefault(open.instrumentKey(), open.entryPrice())));
				return pxMono.flatMap(exitPx -> {
					double pnl = computePnl(open.side(), open.entryPrice(), exitPx, open.quantity());
					DdsTrade closed = open.closed("CLOSED", exitPx, pnl, reason, now);
					return store.updateTrade(closed)
							.flatMap(saved -> bumpRealised(run, pnl).thenReturn(saved));
				});
			});
		}
		return quoteService.ltp(userId, List.of(open.instrumentKey())).flatMap(res -> {
			double exitPx = res.prices().getOrDefault(open.instrumentKey(), open.entryPrice());
			if (exitPx <= 0) {
				exitPx = open.entryPrice();
			}
			double pnl = computePnl(open.side(), open.entryPrice(), exitPx, open.quantity());
			Instant now = Instant.now();
			DdsTrade closed = open.closed("CLOSED", exitPx, pnl, reason, now);
			return store.updateTrade(closed).flatMap(saved -> bumpRealised(run, pnl).thenReturn(saved));
		});
	}

	private Mono<DdsRun> bumpRealised(DdsRun run, double pnl) {
		DdsRun updated = new DdsRun(
				run.id(),
				run.strategyId(),
				run.userId(),
				run.runDate(),
				run.executionMode(),
				run.lifecycleState(),
				run.realisedPnl() + pnl,
				run.unrealisedPnl(),
				run.startedAt(),
				run.endedAt());
		return store.updateRun(updated);
	}

	public Mono<DdsTrade> insertOpenTrade(DdsTrade trade) {
		return store.insertTrade(trade);
	}

	public Mono<Void> markRunActive(DdsStrategy strategy, DdsRun run) {
		return store.compareAndSetLifecycle(run.id(), "ENTERING", "ACTIVE")
				.flatMap(ok -> {
					if (Boolean.TRUE.equals(ok) || "ACTIVE".equals(run.lifecycleState())) {
						return Mono.empty();
					}
					return store.updateRun(run.withLifecycleState("ACTIVE")).then();
				})
				.then(store.updateStrategy(strategy.touched(Instant.now())).then());
	}

	public Mono<Boolean> tryClaimEntry(DdsRun run) {
		return store.compareAndSetLifecycle(run.id(), "WAITING_ENTRY", "ENTERING");
	}

	public Mono<Void> releaseEntryClaimIfIdle(DdsRun run) {
		return store.findTradesByRun(run.id())
				.filter(t -> "OPEN".equals(t.status()))
				.hasElements()
				.flatMap(hasOpen -> {
					if (Boolean.TRUE.equals(hasOpen)) {
						return Mono.empty();
					}
					return store.compareAndSetLifecycle(run.id(), "ENTERING", "WAITING_ENTRY").then();
				});
	}

	public Mono<DdsRun> updateRunMtm(DdsRun run, double unrealised) {
		return store.updateRun(run.withMtm(unrealised));
	}

	public Flux<RunningContext> listRunningStrategies() {
		return store.findStrategiesByStatus("RUNNING")
				.concatMap(strategy -> store.findLatestRun(strategy.id())
						.flatMap(run -> store.findTradesByRun(run.id())
								.filter(t -> "OPEN".equals(t.status()))
								.collectList()
								.map(open -> new RunningContext(
										strategy, run, readSettings(strategy.settingsJson()), open))));
	}

	public record RunningContext(
			DdsStrategy strategy,
			DdsRun run,
			DdsSettings settings,
			List<DdsTrade> openTrades) {
	}

	public record ResolvedLeg(
			String legRole,
			String instrumentKey,
			String tradingSymbol,
			String side,
			int quantity,
			double entryPrice,
			String brokerOrderId,
			Double deltaAtEntry,
			Integer lotSize,
			Double strike,
			String expiry,
			String optionType) {
	}

	public Mono<Void> enterLegs(DdsStrategy strategy, DdsRun run, List<ResolvedLeg> legs, String mode) {
		Instant now = Instant.now();
		List<DdsTrade> trades = new ArrayList<>();
		for (ResolvedLeg leg : legs) {
			trades.add(new DdsTrade(
					UUID.randomUUID(),
					strategy.id(),
					run.id(),
					strategy.userId(),
					leg.legRole(),
					leg.instrumentKey(),
					leg.tradingSymbol(),
					leg.side(),
					leg.quantity(),
					mode,
					leg.brokerOrderId(),
					leg.entryPrice(),
					null,
					0,
					"ENTRY",
					"OPEN",
					leg.deltaAtEntry(),
					leg.lotSize(),
					leg.strike(),
					leg.expiry(),
					leg.optionType(),
					now,
					null));
		}
		return store.insertTrades(trades)
				.then(markRunActive(strategy, run))
				.then(logEvent(strategy.id(), run.id(), strategy.userId(), "ENTRY_FILLED",
						"Entered " + legs.size() + " leg(s) in " + mode))
				.then();
	}

	private Mono<StrategySummaryResponse> finishExit(
			DdsStrategy entity, DdsRun run, UUID userId, double deltaRealised, String reason) {
		if ("MANUAL_STOP".equals(reason)) {
			return finalizeStop(entity, run, userId, deltaRealised);
		}
		return completeSessionAndRearm(entity, run, userId, deltaRealised, reason);
	}

	private Mono<StrategySummaryResponse> finalizeStop(
			DdsStrategy entity, DdsRun run, UUID userId, double deltaRealised) {
		Instant now = Instant.now();
		Mono<Void> stopRun = run == null
				? Mono.empty()
				: store.updateRun(run.stopped(deltaRealised, now)).then();
		return stopRun
				.then(store.updateStrategy(entity.withStatus("SAVED", now)))
				.flatMap(saved -> {
					algoLivePriceLease.release(userId);
					return logEvent(saved.id(), run != null ? run.id() : null, userId, "MANUAL_STOP",
							"Stopped by user").then(toSummary(saved));
				});
	}

	private Mono<StrategySummaryResponse> completeSessionAndRearm(
			DdsStrategy entity, DdsRun run, UUID userId, double deltaRealised, String reason) {
		Instant now = Instant.now();
		LocalDate next = marketCalendar.nextTradingDayAfter(run.runDate() != null ? run.runDate() : LocalDate.now(IST));
		DdsRun stopped = run.stopped(deltaRealised, now);
		DdsRun nextRun = new DdsRun(
				UUID.randomUUID(),
				entity.id(),
				userId,
				next,
				run.executionMode(),
				"WAITING_ENTRY",
				0,
				0,
				now,
				null);
		return store.updateRun(stopped)
				.then(store.insertRun(nextRun))
				.flatMap(nr -> logEvent(entity.id(), nr.id(), userId, "REARMED",
						"Session ended (" + reason + "); re-armed for " + next)
						.then(store.updateStrategy(entity.touched(now))))
				.flatMap(this::toSummary);
	}

	public Mono<Void> logEvent(UUID strategyId, UUID runId, UUID userId, String type, String message) {
		DdsEvent event = new DdsEvent(UUID.randomUUID(), strategyId, runId, userId, type, message, Instant.now());
		return store.insertEvent(event).then();
	}

	public static double computePnl(String side, double entry, double exit, int qty) {
		double diff = "BUY".equalsIgnoreCase(side) ? (exit - entry) : (entry - exit);
		return Math.round(diff * qty * 100.0) / 100.0;
	}

	/** Premium-point PnL (qty / lotSize units of premium). */
	public static double premiumPointPnl(DdsTrade trade, double markPrice) {
		int lot = trade.lotSize() != null && trade.lotSize() > 0 ? trade.lotSize() : 1;
		int lots = Math.max(1, trade.quantity() / lot);
		double exit = "OPEN".equals(trade.status())
				? markPrice
				: (trade.exitPrice() != null ? trade.exitPrice() : trade.entryPrice());
		double perLot = "BUY".equalsIgnoreCase(trade.side())
				? (exit - trade.entryPrice())
				: (trade.entryPrice() - exit);
		return perLot * lots;
	}

	public static boolean maxLossHit(DdsSettings settings, double dayMoneyPnl, double dayPointPnl) {
		if (settings == null || settings.maxLossValue() <= 0) {
			return false;
		}
		String type = settings.maxLossType() != null
				? settings.maxLossType().trim().toUpperCase(Locale.ROOT)
				: "POINTS";
		if (type.startsWith("MONEY") || type.startsWith("ABS") || type.equals("RS") || type.equals("INR")) {
			double budget = settings.maxLossValue() * Math.max(1, settings.lots());
			return dayMoneyPnl <= -budget;
		}
		return dayPointPnl <= -settings.maxLossValue();
	}

	private Mono<StrategySummaryResponse> toSummary(DdsStrategy strategy) {
		DdsSettings settings = readSettings(strategy.settingsJson());
		return store.findLatestRun(strategy.id())
				.flatMap(run -> store.findTradesByRun(run.id()).collectList()
						.map(trades -> buildSummary(strategy, settings, run, trades)))
				.switchIfEmpty(Mono.fromSupplier(() -> buildSummary(strategy, settings, null, List.of())));
	}

	private StrategySummaryResponse buildSummary(
			DdsStrategy strategy, DdsSettings settings, DdsRun run, List<DdsTrade> runTrades) {
		LocalDate today = LocalDate.now(IST);
		List<TradeDto> active = runTrades.stream()
				.filter(t -> "OPEN".equals(t.status()))
				.map(this::toTradeDto)
				.toList();
		double runPnl = runTrades.stream().mapToDouble(DdsTrade::pnl).sum()
				+ (run != null ? run.unrealisedPnl() : 0);
		// Aggregates over this run only for summary speed; total refreshed via trades list path when needed
		int tradesToday = (int) runTrades.stream()
				.filter(t -> t.executedAt() != null && t.executedAt().atZone(IST).toLocalDate().equals(today))
				.count();
		return new StrategySummaryResponse(
				strategy.id(),
				strategy.name(),
				strategy.status(),
				strategy.executionMode(),
				settings,
				run != null ? toRunDto(run) : null,
				active,
				runTrades.size(),
				tradesToday,
				runPnl,
				run != null && run.runDate() != null && run.runDate().equals(today) ? runPnl : 0,
				strategy.createdAt(),
				strategy.updatedAt());
	}

	TradeDto toTradeDto(DdsTrade t) {
		return new TradeDto(
				t.id(), t.strategyId(), t.runId(), t.legRole(), t.instrumentKey(), t.tradingSymbol(), t.side(),
				t.quantity(), t.executionMode(), t.brokerOrderId(), t.entryPrice(), t.exitPrice(), t.pnl(),
				t.triggerReason(), t.status(), t.deltaAtEntry(), t.lotSize(), t.strike(), t.expiry(),
				t.optionType(), t.executedAt(), t.closedAt());
	}

	private RunDto toRunDto(DdsRun r) {
		return new RunDto(
				r.id(), r.strategyId(), r.runDate(), r.executionMode(), r.lifecycleState(), r.realisedPnl(),
				r.unrealisedPnl(), r.startedAt(), r.endedAt());
	}

	private EventDto toEventDto(DdsEvent e) {
		return new EventDto(e.id(), e.strategyId(), e.runId(), e.eventType(), e.message(), e.createdAt());
	}

	private Mono<DdsStrategy> requireOwned(UUID id, UUID userId) {
		return store.findStrategyById(id)
				.switchIfEmpty(Mono.error(new NotFound("Strategy not found")))
				.flatMap(s -> s.userId().equals(userId) ? Mono.just(s) : Mono.error(new Forbidden("Not allowed")));
	}

	static String normalizeMode(String mode) {
		if (mode != null && "LIVE".equalsIgnoreCase(mode.trim())) {
			return "LIVE";
		}
		return "PAPER";
	}

	DdsSettings normalizeSettings(DdsSettings raw) {
		if (raw == null) {
			return DdsSettings.defaults();
		}
		double delta = raw.initialDelta() > 0 ? raw.initialDelta() : 25;
		String lossType = raw.maxLossType() != null && !raw.maxLossType().isBlank()
				? raw.maxLossType().trim().toUpperCase(Locale.ROOT)
				: "POINTS";
		if (!lossType.startsWith("MONEY") && !lossType.startsWith("POINT") && !lossType.startsWith("ABS")) {
			lossType = "POINTS";
		}
		else if (lossType.startsWith("MONEY") || lossType.startsWith("ABS")) {
			lossType = "MONEY";
		}
		else {
			lossType = "POINTS";
		}
		String exit = raw.exitTime() != null && !raw.exitTime().isBlank() ? raw.exitTime().trim() : "15:00";
		int lots = Math.max(1, raw.lots());
		return new DdsSettings(delta, lossType, Math.max(0, raw.maxLossValue()), exit, lots);
	}

	String writeSettings(DdsSettings settings) {
		try {
			return jsonMapper.writeValueAsString(settings);
		}
		catch (Exception ex) {
			throw new ValidationFailed("Could not serialize DDS settings");
		}
	}

	DdsSettings readSettings(String json) {
		if (json == null || json.isBlank()) {
			return DdsSettings.defaults();
		}
		try {
			return normalizeSettings(jsonMapper.readValue(json, DdsSettings.class));
		}
		catch (Exception ex) {
			log.warn("Bad DDS settings JSON: {}", ex.toString());
			return DdsSettings.defaults();
		}
	}

	private static Map<String, PositionItem> indexOpenPositions(PositionsResponse snapshot) {
		Map<String, PositionItem> map = new HashMap<>();
		if (snapshot == null || snapshot.openGroups() == null) {
			return map;
		}
		for (var group : snapshot.openGroups()) {
			for (PositionItem item : group.positions()) {
				map.put(item.instrumentKey(), item);
			}
		}
		return map;
	}

	private static PositionsResponse emptyPositions() {
		return new PositionsResponse(List.of(), List.of(), 0, 0, null, null, 0, 0, "empty");
	}

	static LocalTime parseTime(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			String t = raw.trim();
			if (t.length() == 4) {
				t = "0" + t;
			}
			return LocalTime.parse(t.length() == 5 ? t : t.substring(0, 5));
		}
		catch (Exception ex) {
			return null;
		}
	}
}

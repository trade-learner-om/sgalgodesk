package com.sgalgodesk.desk.application.algo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.EventDto;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.RunDto;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.RunStrategyRequest;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.SaveStrategyRequest;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.StrategyConfig;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.StrategyLifecycleResponse;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.StrategySummaryResponse;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.TradeDto;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.positions.PositionBookService;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;
import com.sgalgodesk.desk.application.quote.QuoteService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.application.algo.port.AlgoStrategyStorePort;
import com.sgalgodesk.desk.domain.algo.AlgoStrategy;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyEvent;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;
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
public class AlgoStrategyService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final AlgoStrategyStorePort store;
	private final PositionBookService positionBookService;
	private final OrderService orderService;
	private final QuoteService quoteService;
	private final AlgoLivePriceLease algoLivePriceLease;
	private final MstockMarketCalendarService marketCalendar;
	private final JsonMapper jsonMapper;

	public Mono<List<StrategySummaryResponse>> listStrategies(UUID userId) {
		return store.findStrategiesByUser(userId)
				.concatMap(this::toSummary)
				.collectList();
	}

	public Mono<StrategySummaryResponse> createStrategy(UUID userId, SaveStrategyRequest req) {
		Instant now = Instant.now();
		AlgoStrategy strategy = new AlgoStrategy(
				UUID.randomUUID(),
				userId,
				req.name().trim(),
				"SAVED",
				normalizeMode(req.executionMode()),
				writeConfig(req.config()),
				now,
				now);
		return store.insertStrategy(strategy)
				.flatMap(saved -> logEvent(saved.id(), null, userId, "CREATED",
						"Strategy saved: " + saved.name()).thenReturn(saved))
				.flatMap(this::toSummary);
	}

	public Mono<StrategySummaryResponse> updateStrategy(UUID userId, UUID id, SaveStrategyRequest req) {
		return requireOwnedStrategy(id, userId)
				.flatMap(existing -> {
					if ("RUNNING".equals(existing.status())) {
						return Mono.error(new Conflict("Stop the running strategy before editing"));
					}
					String mode = req.executionMode() != null && !req.executionMode().isBlank()
							? normalizeMode(req.executionMode())
							: existing.executionMode();
					AlgoStrategy updated = existing.withDefinition(
							req.name().trim(), mode, writeConfig(req.config()), Instant.now());
					return store.updateStrategy(updated)
							.flatMap(saved -> logEvent(saved.id(), null, userId, "UPDATED",
									"Strategy configuration updated").thenReturn(saved))
							.flatMap(this::toSummary);
				});
	}

	public Mono<Void> deleteStrategy(UUID userId, UUID id) {
		return requireOwnedStrategy(id, userId)
				.flatMap(existing -> {
					if ("RUNNING".equals(existing.status())) {
						return Mono.error(new Conflict("Stop the running strategy before deleting"));
					}
					return store.deleteStrategy(existing.id());
				});
	}

	public Mono<StrategySummaryResponse> runStrategy(UUID userId, UUID id, RunStrategyRequest req) {
		String mode = normalizeMode(req.executionMode());
		return requireOwnedStrategy(id, userId)
				.flatMap(existing -> {
					if ("RUNNING".equals(existing.status())) {
						return Mono.error(new Conflict("Strategy is already running"));
					}
					Instant now = Instant.now();
					AlgoStrategy running = existing.withRunning(mode, now);
					AlgoStrategyRun run = new AlgoStrategyRun(
							UUID.randomUUID(),
							running.id(),
							userId,
							LocalDate.now(IST),
							mode,
							"WAITING_ENTRY",
							0,
							0,
							0,
							null,
							0,
							now,
							null);
					return store.updateStrategy(running)
							.then(store.insertRun(run))
							.flatMap(savedRun -> logEvent(running.id(), savedRun.id(), userId,
									"RUN_STARTED", "Started in " + mode + " mode")
									.thenReturn(running))
							.flatMap(this::toSummary);
				});
	}

	public Mono<StrategySummaryResponse> stopStrategy(UUID userId, UUID id) {
		return requireOwnedStrategy(id, userId)
				.flatMap(entity -> {
					if (!"RUNNING".equals(entity.status())) {
						return Mono.error(new ValidationFailed("Strategy is not running"));
					}
					return store.findLatestRun(entity.id())
							.flatMap(run -> exitRun(entity, run, userId, "MANUAL_STOP"))
							.switchIfEmpty(Mono.defer(() -> finalizeStop(entity, null, userId, 0.0)));
				});
	}

	/** Engine / stop path: close OPEN trades, then stop (manual) or re-arm for next session. */
	public Mono<StrategySummaryResponse> exitRun(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			String triggerReason) {
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
						return stopLiveWithBrokerCheck(entity, run, userId, openTrades, reason);
					}
					return stopPaperTrades(entity, run, userId, openTrades, reason);
				});
	}

	private Mono<StrategySummaryResponse> stopLiveWithBrokerCheck(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> openTrades,
			String triggerReason) {
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(openTrades);
		return cancelRestingStopLosses(entity, run, userId, ordered)
				.then(positionBookService.snapshot(userId))
				.flatMap(snapshot -> {
					Map<String, PositionItem> brokerOpen = indexOpenPositions(snapshot);
					List<AlgoStrategyTrade> alreadyClosed = new ArrayList<>();
					List<AlgoStrategyTrade> toExit = new ArrayList<>();

					for (AlgoStrategyTrade trade : ordered) {
						PositionItem pos = brokerOpen.get(trade.instrumentKey());
						if (pos == null || pos.quantity() == 0) {
							alreadyClosed.add(trade);
							continue;
						}
						boolean sameDirection = ("BUY".equalsIgnoreCase(trade.side()) && pos.quantity() > 0)
								|| ("SELL".equalsIgnoreCase(trade.side()) && pos.quantity() < 0);
						if (!sameDirection) {
							alreadyClosed.add(trade);
							continue;
						}
						toExit.add(trade);
					}

					Instant now = Instant.now();
					Mono<Void> reconcileMono = Flux.fromIterable(alreadyClosed)
							.concatMap(trade -> {
								AlgoStrategyTrade reconciled = trade.closed(
										"RECONCILED_CLOSED", trade.exitPrice(), trade.pnl(), triggerReason, now);
								return store.updateTrade(reconciled)
										.then(logEvent(entity.id(), run.id(), userId,
												"BROKER_RECONCILED",
												"Position already flat at broker: " + trade.tradingSymbol()));
							})
							.then();

					if (toExit.isEmpty()) {
						return reconcileMono.then(finishExit(entity, run, userId, 0.0, triggerReason));
					}

					return reconcileMono.then(exitLiveLegsSequential(
							entity, run, userId, toExit, brokerOpen, triggerReason, true));
				});
	}

	/**
	 * Cancels resting SL-M orders, then MARKET-exits legs in SELL-then-BUY order.
	 * When {@code finalize} is true, stops the strategy after; otherwise leaves run ACTIVE
	 * if other legs remain open.
	 */
	private Mono<StrategySummaryResponse> exitLiveLegsSequential(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> toExit,
			Map<String, PositionItem> brokerOpen,
			String triggerReason,
			boolean finalize) {
		String tag = "algo-" + entity.id().toString().substring(0, 8);
		Instant now = Instant.now();
		List<AlgoStrategyTrade> closed = new ArrayList<>();
		List<AlgoStrategyTrade> failed = new ArrayList<>();

		return Flux.fromIterable(toExit)
				.concatMap(trade -> {
					PositionItem pos = brokerOpen != null ? brokerOpen.get(trade.instrumentKey()) : null;
					int qtyToClose = pos != null
							? Math.min(trade.quantity(), Math.abs(pos.quantity()))
							: trade.quantity();
					String product = pos != null && pos.product() != null ? pos.product() : "I";
					String exitSide = "BUY".equalsIgnoreCase(trade.side()) ? "SELL" : "BUY";
					PlaceOrderBody body = new PlaceOrderBody(
							trade.instrumentKey(),
							exitSide,
							qtyToClose,
							product,
							"MARKET",
							"DAY",
							0.0,
							null,
							tag,
							Boolean.TRUE);
					return orderService.place(userId, body)
							.flatMap(placed -> {
								String orderId = placed.orderIds() != null && !placed.orderIds().isEmpty()
										? placed.orderIds().getFirst()
										: null;
								if (orderId == null || (placed.status() != null
										&& "error".equalsIgnoreCase(placed.status()))) {
									failed.add(trade);
									return logEvent(entity.id(), run.id(), userId, "EXIT_FAILED",
											"Exit order failed for " + trade.tradingSymbol());
								}
								return orderService.waitUntilComplete(userId, orderId)
										.onErrorResume(err -> Mono.empty())
										.flatMap(fill -> {
											double exitPx = fill != null && fill.averagePrice() > 0
													? fill.averagePrice()
													: (pos != null && pos.lastPrice() > 0
															? pos.lastPrice()
															: trade.entryPrice());
											double pnl = computePnl(
													trade.side(), trade.entryPrice(), exitPx, trade.quantity());
											AlgoStrategyTrade closedTrade = trade.closed(
													"CLOSED", exitPx, pnl, triggerReason, now);
											closed.add(closedTrade);
											return store.updateTrade(closedTrade).then();
										})
										.switchIfEmpty(Mono.defer(() -> {
											double exitPx = pos != null && pos.lastPrice() > 0
													? pos.lastPrice()
													: trade.entryPrice();
											double pnl = computePnl(
													trade.side(), trade.entryPrice(), exitPx, trade.quantity());
											AlgoStrategyTrade closedTrade = trade.closed(
													"CLOSED", exitPx, pnl, triggerReason, now);
											closed.add(closedTrade);
											return store.updateTrade(closedTrade).then();
										}));
							})
							.onErrorResume(err -> {
								failed.add(trade);
								return logEvent(entity.id(), run.id(), userId, "EXIT_FAILED",
										err.getMessage() != null ? err.getMessage() : err.toString());
							});
				})
				.then(Mono.defer(() -> {
					double delta = closed.stream().mapToDouble(AlgoStrategyTrade::pnl).sum();
					Mono<Void> log = logEvent(entity.id(), run.id(), userId, "LIVE_EXIT_PLACED",
							"Exited " + closed.size() + " leg(s) (" + triggerReason + ")"
									+ (failed.isEmpty() ? "" : "; " + failed.size() + " failed"));
					if (!finalize) {
						return log.then(toSummary(entity));
					}
					if (!failed.isEmpty()) {
						return log.then(logEvent(entity.id(), run.id(), userId, "EXIT_INCOMPLETE",
										"Some legs remain open; strategy kept RUNNING for retry"))
								.then(toSummary(entity));
					}
					return log.then(finishExit(entity, run, userId, delta, triggerReason));
				}));
	}

	private Mono<Void> cancelRestingStopLosses(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> trades) {
		return Flux.fromIterable(trades)
				.filter(t -> t.slOrderId() != null && !t.slOrderId().isBlank())
				.concatMap(t -> orderService.cancelOrder(userId, t.slOrderId())
						.then(logEvent(entity.id(), run.id(), userId, "SL_CANCELLED",
								"Cancelled SL " + t.slOrderId() + " for " + t.tradingSymbol()))
						.onErrorResume(err -> logEvent(entity.id(), run.id(), userId, "SL_CANCEL_FAILED",
								t.slOrderId() + ": " + err.getMessage())))
				.then();
	}

	private Mono<StrategySummaryResponse> stopPaperTrades(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> openTrades,
			String triggerReason) {
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(openTrades);
		List<String> keys = ordered.stream().map(AlgoStrategyTrade::instrumentKey).distinct().toList();
		return quoteService.ltp(userId, keys)
				.map(res -> res.prices())
				.onErrorReturn(Map.of())
				.flatMap(prices -> {
					Instant now = Instant.now();
					double deltaRealised = 0.0;
					List<AlgoStrategyTrade> closedTrades = new ArrayList<>();
					for (AlgoStrategyTrade t : ordered) {
						double exitPx = prices.getOrDefault(t.instrumentKey(), t.entryPrice());
						double pnl = computePnl(t.side(), t.entryPrice(), exitPx, t.quantity());
						deltaRealised += pnl;
						closedTrades.add(t.closed("CLOSED", exitPx, pnl, triggerReason, now));
					}
					double finalDelta = deltaRealised;
					return store.updateTrades(closedTrades)
							.then(logEvent(entity.id(), run.id(), userId, "PAPER_EXIT",
									"Exited " + ordered.size() + " paper leg(s) (" + triggerReason + ")"))
							.then(finishExit(entity, run, userId, finalDelta, triggerReason));
				});
	}

	/**
	 * Manual stop → SAVED. Any auto exit → end the run and re-arm WAITING_ENTRY for the next
	 * trading day while keeping strategy RUNNING.
	 */
	private Mono<StrategySummaryResponse> finishExit(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			double deltaRealised,
			String triggerReason) {
		if ("MANUAL_STOP".equals(triggerReason)) {
			return finalizeStop(entity, run, userId, deltaRealised);
		}
		return completeSessionAndRearm(entity, run, userId, deltaRealised, triggerReason);
	}

	private Mono<StrategySummaryResponse> finalizeStop(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			double deltaRealised) {
		Instant now = Instant.now();
		AlgoStrategy saved = entity.withStatus("SAVED", now);
		Mono<AlgoStrategyRun> runSave = run == null
				? Mono.empty()
				: Mono.defer(() -> store.updateRun(run.stopped(deltaRealised, now)));
		return store.updateStrategy(saved)
				.then(runSave.then())
				.then(logEvent(saved.id(), run != null ? run.id() : null, userId,
						"STOPPED", "Strategy stopped"))
				.then(releaseLivePriceLeaseIfIdle(userId))
				.then(toSummary(saved));
	}

	/** End the current run and open a fresh WAITING_ENTRY run for the next NSE trading day. */
	private Mono<StrategySummaryResponse> completeSessionAndRearm(
			AlgoStrategy entity,
			AlgoStrategyRun run,
			UUID userId,
			double deltaRealised,
			String triggerReason) {
		Instant now = Instant.now();
		LocalDate nextDay = marketCalendar.nextTradingDayAfter(LocalDate.now(IST));
		String reason = triggerReason != null ? triggerReason : "SESSION_COMPLETE";
		Mono<Void> stopCurrent = run == null
				? Mono.empty()
				: store.updateRun(run.stopped(deltaRealised, now)).then();
		AlgoStrategyRun nextRun = new AlgoStrategyRun(
				UUID.randomUUID(),
				entity.id(),
				userId,
				nextDay,
				run != null ? run.executionMode() : entity.executionMode(),
				"WAITING_ENTRY",
				0,
				0,
				0,
				null,
				0,
				now,
				null);
		return stopCurrent
				.then(logEvent(entity.id(), run != null ? run.id() : null, userId,
						"SESSION_COMPLETE",
						"Session ended (" + reason + "); re-arming for " + nextDay))
				.then(store.insertRun(nextRun))
				.flatMap(savedRun -> logEvent(entity.id(), savedRun.id(), userId,
						"REARMED", "Waiting for entry on " + nextDay)
						.thenReturn(entity))
				.flatMap(this::toSummary);
	}

	/** Drop the algo live-price lease when this user has no other RUNNING strategies. */
	private Mono<Void> releaseLivePriceLeaseIfIdle(UUID userId) {
		return store.findStrategiesByUser(userId)
				.filter(s -> "RUNNING".equals(s.status()))
				.hasElements()
				.doOnNext(anyRunning -> {
					if (!anyRunning) {
						algoLivePriceLease.release(userId);
					}
				})
				.then();
	}

	public Mono<StrategyLifecycleResponse> getLifecycle(UUID userId, UUID id) {
		return requireOwnedStrategy(id, userId)
				.flatMap(entity -> Mono.zip(
						toSummary(entity),
						store.findRunsByStrategy(id).map(this::toRunDto).collectList(),
						store.findTradesByStrategy(id).map(this::toTradeDto).collectList(),
						store.findEventsByStrategy(id).map(this::toEventDto).collectList())
						.map(t -> new StrategyLifecycleResponse(t.getT1(), t.getT2(), t.getT3(), t.getT4())));
	}

	public Mono<Void> logEvent(UUID strategyId, UUID runId, UUID userId, String type, String message) {
		AlgoStrategyEvent ev = new AlgoStrategyEvent(
				UUID.randomUUID(), strategyId, runId, userId, type, message, Instant.now());
		return store.insertEvent(ev).then();
	}

	private Mono<AlgoStrategy> requireOwnedStrategy(UUID id, UUID userId) {
		return store.findStrategyById(id)
				.switchIfEmpty(Mono.error(new NotFound("Strategy not found")))
				.flatMap(s -> userId.equals(s.userId())
						? Mono.just(s)
						: Mono.error(new Forbidden("Not allowed")));
	}

	private Mono<StrategySummaryResponse> toSummary(AlgoStrategy entity) {
		StrategyConfig config = readConfig(entity.configJson());
		LocalDate today = LocalDate.now(IST);
		return store.findTradesByStrategy(entity.id())
				.collectList()
				.flatMap(trades -> store.findLatestRun(entity.id())
						.flatMap(run -> {
							StatsAggregate stats = computeStats(trades, run, today);
							return store.findTradesByRun(run.id())
									.map(this::toTradeDto)
									.collectList()
									.map(activeTrades -> new StrategySummaryResponse(
											entity.id(),
											entity.name(),
											entity.status(),
											entity.executionMode(),
											config,
											toRunDto(run),
											activeTrades,
											stats.totalTrades(),
											stats.tradesToday(),
											stats.totalPnl(),
											stats.todayPnl(),
											entity.createdAt(),
											entity.updatedAt()));
						})
						.switchIfEmpty(Mono.fromSupplier(() -> {
							StatsAggregate stats = computeStats(trades, null, today);
							return new StrategySummaryResponse(
									entity.id(),
									entity.name(),
									entity.status(),
									entity.executionMode(),
									config,
									null,
									List.of(),
									stats.totalTrades(),
									stats.tradesToday(),
									stats.totalPnl(),
									stats.todayPnl(),
									entity.createdAt(),
									entity.updatedAt());
						})));
	}

	private record StatsAggregate(int totalTrades, int tradesToday, double totalPnl, double todayPnl) {
	}

	private static StatsAggregate computeStats(
			List<AlgoStrategyTrade> trades,
			AlgoStrategyRun run,
			LocalDate today) {
		int totalTrades = trades.size();
		int tradesToday = (int) trades.stream()
				.filter(t -> t.executedAt() != null
						&& t.executedAt().atZone(IST).toLocalDate().equals(today))
				.count();
		double closedPnl = trades.stream().mapToDouble(AlgoStrategyTrade::pnl).sum();
		double unrealised = run != null && !"STOPPED".equals(run.lifecycleState())
				? run.unrealisedPnl()
				: 0.0;
		double totalPnl = Math.round((closedPnl + unrealised) * 100.0) / 100.0;
		double todayClosed = trades.stream()
				.filter(t -> {
					Instant ts = t.closedAt() != null ? t.closedAt() : t.executedAt();
					return ts != null && ts.atZone(IST).toLocalDate().equals(today);
				})
				.mapToDouble(AlgoStrategyTrade::pnl)
				.sum();
		boolean runToday = run != null && today.equals(run.runDate())
				&& !"STOPPED".equals(run.lifecycleState());
		double todayPnl = Math.round((todayClosed + (runToday ? unrealised : 0.0)) * 100.0) / 100.0;
		return new StatsAggregate(totalTrades, tradesToday, totalPnl, todayPnl);
	}

	private RunDto toRunDto(AlgoStrategyRun r) {
		return new RunDto(
				r.id(), r.strategyId(), r.runDate(), r.executionMode(),
				r.lifecycleState(), r.realisedPnl(), r.unrealisedPnl(),
				r.reentryCount(), r.trailFloorPnl(), r.peakPnl(),
				r.startedAt(), r.endedAt());
	}

	private TradeDto toTradeDto(AlgoStrategyTrade t) {
		return new TradeDto(
				t.id(), t.strategyId(), t.runId(), t.legIndex(),
				t.instrumentKey(), t.tradingSymbol(), t.side(), t.quantity(),
				t.executionMode(), t.brokerOrderId(), t.slOrderId(), t.slTriggerPrice(),
				t.entryPrice(), t.exitPrice(),
				t.pnl(), t.triggerReason(), t.status(), t.executedAt(), t.closedAt(),
				t.lotSize(), t.strike(), t.expiry(), t.optionType());
	}

	private EventDto toEventDto(AlgoStrategyEvent e) {
		return new EventDto(e.id(), e.strategyId(), e.runId(), e.eventType(), e.message(), e.createdAt());
	}

	public StrategyConfig readConfig(String json) {
		try {
			return jsonMapper.readValue(json, StrategyConfig.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid stored strategy config", ex);
		}
	}

	private String writeConfig(StrategyConfig config) {
		try {
			return jsonMapper.writeValueAsString(config);
		}
		catch (Exception ex) {
			throw new ValidationFailed("Could not serialize strategy config");
		}
	}

	private static String normalizeMode(String mode) {
		if (mode != null && "LIVE".equalsIgnoreCase(mode.trim())) {
			return "LIVE";
		}
		return "PAPER";
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

	public static double computePnl(String side, double entry, double exit, int qty) {
		double diff = "BUY".equalsIgnoreCase(side) ? (exit - entry) : (entry - exit);
		return Math.round(diff * qty * 100.0) / 100.0;
	}

	// --- Engine support -------------------------------------------------------

	public record RunningStrategyContext(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			StrategyConfig config,
			List<AlgoStrategyTrade> openTrades) {
	}

	public record ResolvedLeg(
			int legIndex,
			String instrumentKey,
			String tradingSymbol,
			String side,
			int quantity,
			double entryPrice,
			String brokerOrderId,
			String slOrderId,
			Double slTriggerPrice,
			Integer lotSize,
			Double strike,
			String expiry,
			String optionType) {
	}

	public Flux<RunningStrategyContext> listRunningStrategies() {
		return store.findStrategiesByStatus("RUNNING")
				.concatMap(strategy -> store.findLatestRun(strategy.id())
						.flatMap(run -> store.findTradesByRun(run.id())
								.filter(t -> "OPEN".equals(t.status()))
								.collectList()
								.map(open -> new RunningStrategyContext(
										strategy,
										run,
										readConfig(strategy.configJson()),
										open))));
	}

	public Mono<Void> enterLegs(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			List<ResolvedLeg> legs,
			String mode) {
		Instant now = Instant.now();
		List<AlgoStrategyTrade> trades = new ArrayList<>();
		for (ResolvedLeg leg : legs) {
			trades.add(toOpenTrade(strategy, run, leg, mode, now));
		}
		return store.insertTrades(trades)
				.then(markRunActive(strategy, run))
				.then(logEvent(strategy.id(), run.id(), strategy.userId(),
						"ENTRY_FILLED", "Entered " + legs.size() + " leg(s) in " + mode))
				.then();
	}

	public Mono<AlgoStrategyTrade> insertOpenTrade(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			ResolvedLeg leg,
			String mode) {
		AlgoStrategyTrade trade = toOpenTrade(strategy, run, leg, mode, Instant.now());
		return store.insertTrade(trade);
	}

	public Mono<AlgoStrategyTrade> updateTrade(AlgoStrategyTrade trade) {
		return store.updateTrade(trade);
	}

	public Mono<Void> markRunActive(AlgoStrategy strategy, AlgoStrategyRun run) {
		Instant now = Instant.now();
		return store.updateRun(run.withLifecycleState("ACTIVE"))
				.then(store.updateStrategy(strategy.touched(now)))
				.then();
	}

	/**
	 * Atomically claim entry: {@code WAITING_ENTRY → ENTERING}.
	 * Returns false if another tick already claimed (or state is not WAITING_ENTRY).
	 */
	public Mono<Boolean> tryClaimEntry(AlgoStrategyRun run) {
		return store.compareAndSetLifecycle(run.id(), "WAITING_ENTRY", "ENTERING");
	}

	/**
	 * After a failed entry attempt: if no OPEN trades remain, release
	 * {@code ENTERING → WAITING_ENTRY} so the engine can retry. Leaves ENTERING
	 * alone when partial opens still exist (resume on next tick).
	 */
	public Mono<Void> releaseEntryClaimIfIdle(AlgoStrategyRun run) {
		return loadOpenTrades(run.id()).flatMap(open -> {
			if (open != null && !open.isEmpty()) {
				return Mono.empty();
			}
			return store.compareAndSetLifecycle(run.id(), "ENTERING", "WAITING_ENTRY").then();
		});
	}

	private static AlgoStrategyTrade toOpenTrade(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			ResolvedLeg leg,
			String mode,
			Instant now) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(),
				strategy.id(),
				run.id(),
				strategy.userId(),
				leg.legIndex(),
				leg.instrumentKey(),
				leg.tradingSymbol(),
				leg.side(),
				leg.quantity(),
				mode,
				leg.brokerOrderId(),
				leg.slOrderId(),
				leg.slTriggerPrice(),
				leg.entryPrice(),
				null,
				0,
				"ENTRY",
				"OPEN",
				now,
				null,
				leg.lotSize(),
				leg.strike(),
				leg.expiry(),
				leg.optionType());
	}

	/**
	 * Fail-closed: MARKET-unwind fills from this entry attempt only. Does not end the
	 * session — releases {@code ENTERING → WAITING_ENTRY} when no OPEN trades remain so
	 * entry can retry; keeps ENTERING when prior/partial opens still exist.
	 */
	public Mono<Void> unwindPartialLiveEntry(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> filled,
			String reason) {
		Mono<Void> unwind;
		if (filled == null || filled.isEmpty()) {
			unwind = logEvent(strategy.id(), run.id(), userId, "ENTRY_FAILED", "No fills to unwind")
					.then();
		}
		else {
			List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(filled);
			unwind = cancelRestingStopLosses(strategy, run, userId, ordered)
					.then(positionBookService.snapshot(userId).onErrorReturn(emptyPositions()))
					.flatMap(snapshot -> {
						Map<String, PositionItem> brokerOpen = indexOpenPositions(snapshot);
						return exitLiveLegsSequential(
								strategy, run, userId, ordered, brokerOpen, reason, false)
								.then();
					});
		}
		return unwind.then(releaseEntryClaimIfIdle(run));
	}

	/** Close selected legs (software per-leg SL). Finalize if no OPEN legs remain. */
	public Mono<StrategySummaryResponse> exitSelectedLegs(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> legs,
			String reason) {
		if (legs == null || legs.isEmpty()) {
			return toSummary(strategy);
		}
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(legs);
		StrategyConfig config = readConfig(strategy.configJson());
		if ("LIVE".equalsIgnoreCase(run.executionMode())) {
			return cancelRestingStopLosses(strategy, run, userId, ordered)
					.then(positionBookService.snapshot(userId))
					.flatMap(snapshot -> {
						Map<String, PositionItem> brokerOpen = indexOpenPositions(snapshot);
						return exitLiveLegsSequential(
								strategy, run, userId, ordered, brokerOpen, reason, false)
								.flatMap(summary -> loadOpenTrades(run.id())
										.flatMap(remaining -> {
											if (remaining.isEmpty()) {
												String endReason = reason != null && !reason.isBlank()
														? reason
														: "LEG_SL";
												return finishExit(strategy, run, userId, 0.0, endReason);
											}
											return applyOverallBudgetAfterPartialExit(
													strategy, run, userId, config, remaining)
													.thenReturn(summary);
										}));
					});
		}
		List<String> keys = ordered.stream().map(AlgoStrategyTrade::instrumentKey).distinct().toList();
		return quoteService.ltp(userId, keys)
				.map(res -> res.prices())
				.onErrorReturn(Map.of())
				.flatMap(prices -> {
					Instant now = Instant.now();
					List<AlgoStrategyTrade> closed = new ArrayList<>();
					double delta = 0;
					for (AlgoStrategyTrade t : ordered) {
						double exitPx = prices.getOrDefault(t.instrumentKey(), t.entryPrice());
						double pnl = computePnl(t.side(), t.entryPrice(), exitPx, t.quantity());
						delta += pnl;
						closed.add(t.closed("CLOSED", exitPx, pnl, reason, now));
					}
					double finalDelta = delta;
					String endReason = reason != null && !reason.isBlank() ? reason : "LEG_SL";
					return store.updateTrades(closed)
							.then(logEvent(strategy.id(), run.id(), userId, "LEG_SL",
									"Software SL closed " + closed.size() + " leg(s)"))
							.then(loadOpenTrades(run.id()))
							.flatMap(remaining -> {
								if (remaining.isEmpty()) {
									return finishExit(strategy, run, userId, finalDelta, endReason);
								}
								return applyOverallBudgetAfterPartialExit(
										strategy, run, userId, config, remaining)
										.then(store.updateRun(run.withMtm(
												run.unrealisedPnl(),
												run.peakPnl(),
												run.trailFloorPnl())))
										.then(toSummary(strategy));
							});
				});
	}

	/**
	 * After a partial leg exit: if overall SL budget is exhausted, square remaining legs;
	 * otherwise tighten remaining SL triggers to entry ± remaining points.
	 */
	public Mono<Void> applyOverallBudgetAfterPartialExit(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			UUID userId,
			StrategyConfig config,
			List<AlgoStrategyTrade> remainingOpen) {
		var overall = config != null ? config.overall() : null;
		if (overall == null || !overall.overallSlEnabled() || overall.overallSlValue() == null
				|| !AlgoOverallMath.isPointsOrPremium(overall.overallSlType())) {
			return Mono.empty();
		}
		return loadClosedTrades(run.id())
				.flatMap(closed -> {
					double remaining = AlgoOverallMath.remainingOverallBudget(
							overall.overallSlValue(), closed);
					if (remaining <= 0) {
						return exitRun(strategy, run, userId, "OVERALL_SL").then();
					}
					return Flux.fromIterable(remainingOpen)
							.concatMap(trade -> tightenAliveLegSl(
									strategy, run, userId, trade, remaining))
							.then();
				});
	}

	private Mono<AlgoStrategyTrade> tightenAliveLegSl(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			UUID userId,
			AlgoStrategyTrade trade,
			double remainingPoints) {
		Double newTrigger = AlgoOverallMath.tightenedSlTrigger(
				trade.side(), trade.entryPrice(), remainingPoints, trade.slTriggerPrice());
		if (newTrigger == null) {
			return Mono.just(trade);
		}
		if (trade.slTriggerPrice() != null
				&& Math.abs(trade.slTriggerPrice() - newTrigger) < 0.005) {
			return Mono.just(trade);
		}
		if (!"LIVE".equalsIgnoreCase(run.executionMode())) {
			AlgoStrategyTrade updated = trade.withSl(trade.slOrderId(), newTrigger);
			return store.updateTrade(updated)
					.then(logEvent(strategy.id(), run.id(), userId, "SL_TIGHTENED",
							trade.tradingSymbol() + " software SL → " + newTrigger
									+ " (overall remaining " + remainingPoints + " pts)"))
					.thenReturn(updated);
		}
		Mono<Void> cancel = (trade.slOrderId() != null && !trade.slOrderId().isBlank())
				? orderService.cancelOrder(userId, trade.slOrderId())
						.then(logEvent(strategy.id(), run.id(), userId, "SL_CANCELLED",
								"Cancelled SL " + trade.slOrderId() + " before tighten"))
						.onErrorResume(err -> logEvent(strategy.id(), run.id(), userId, "SL_CANCEL_FAILED",
								trade.slOrderId() + ": " + err.getMessage()))
				: Mono.empty();
		String exitSide = "BUY".equalsIgnoreCase(trade.side()) ? "SELL" : "BUY";
		String tag = "algo-sl-" + strategy.id().toString().substring(0, 8);
		PlaceOrderBody slOrder = new PlaceOrderBody(
				trade.instrumentKey(),
				exitSide,
				trade.quantity(),
				"I",
				"SL-M",
				"DAY",
				0.0,
				newTrigger,
				tag,
				Boolean.TRUE);
		return cancel.then(orderService.place(userId, slOrder))
				.flatMap(placed -> {
					String slId = placed.orderIds() != null && !placed.orderIds().isEmpty()
							? placed.orderIds().getFirst()
							: null;
					if (slId == null || (placed.status() != null && "error".equalsIgnoreCase(placed.status()))) {
						AlgoStrategyTrade soft = trade.withSl(null, newTrigger);
						return store.updateTrade(soft)
								.then(logEvent(strategy.id(), run.id(), userId, "SL_PLACE_FAILED",
										"Tighten SL-M failed for " + trade.tradingSymbol()
												+ "; software SL @ " + newTrigger))
								.thenReturn(soft);
					}
					AlgoStrategyTrade withSl = trade.withSl(slId, newTrigger);
					return store.updateTrade(withSl)
							.then(logEvent(strategy.id(), run.id(), userId, "SL_TIGHTENED",
									"SL-M " + slId + " @ " + newTrigger + " for " + trade.tradingSymbol()
											+ " (overall remaining " + remainingPoints + " pts)"))
							.thenReturn(withSl);
				})
				.onErrorResume(err -> {
					AlgoStrategyTrade soft = trade.withSl(null, newTrigger);
					return store.updateTrade(soft)
							.then(logEvent(strategy.id(), run.id(), userId, "SL_PLACE_FAILED",
									err.getMessage() != null ? err.getMessage() : err.toString()))
							.thenReturn(soft);
				});
	}

	/** Reconcile OPEN trades that are already flat at the broker (e.g. SL-M filled). */
	public Mono<List<AlgoStrategyTrade>> reconcileFlatLegs(
			AlgoStrategy strategy,
			AlgoStrategyRun run,
			UUID userId,
			List<AlgoStrategyTrade> open) {
		return positionBookService.snapshot(userId)
				.onErrorReturn(emptyPositions())
				.flatMap(snapshot -> {
					Map<String, PositionItem> brokerOpen = indexOpenPositions(snapshot);
					Instant now = Instant.now();
					List<AlgoStrategyTrade> stillOpen = new ArrayList<>();
					List<AlgoStrategyTrade> flat = new ArrayList<>();
					for (AlgoStrategyTrade trade : open) {
						PositionItem pos = brokerOpen.get(trade.instrumentKey());
						boolean openAtBroker = pos != null && pos.quantity() != 0
								&& (("BUY".equalsIgnoreCase(trade.side()) && pos.quantity() > 0)
										|| ("SELL".equalsIgnoreCase(trade.side()) && pos.quantity() < 0));
						if (openAtBroker) {
							stillOpen.add(trade);
						}
						else {
							// Prefer SL trigger as exit mark when broker SL-M filled
							double exitPx = trade.slTriggerPrice() != null && trade.slTriggerPrice() > 0
									? trade.slTriggerPrice()
									: trade.entryPrice();
							double pnl = computePnl(trade.side(), trade.entryPrice(), exitPx, trade.quantity());
							flat.add(trade.closed("RECONCILED_CLOSED", exitPx, pnl, "LEG_SL", now));
						}
					}
					if (flat.isEmpty()) {
						return Mono.just(stillOpen);
					}
					return store.updateTrades(flat)
							.then(Flux.fromIterable(flat)
									.concatMap(t -> logEvent(strategy.id(), run.id(), userId,
											"BROKER_RECONCILED",
											"Flat at broker (likely SL): " + t.tradingSymbol()))
									.then())
							.thenReturn(stillOpen);
				});
	}

	/** Persists mark-to-market values and emits the updated run. */
	public Mono<AlgoStrategyRun> updateRunMtm(
			AlgoStrategyRun run,
			double unrealisedPnl,
			double peakPnl,
			Double trailFloorPnl) {
		return store.updateRun(run.withMtm(unrealisedPnl, peakPnl, trailFloorPnl));
	}

	public Mono<List<AlgoStrategyTrade>> loadOpenTrades(UUID runId) {
		return store.findTradesByRun(runId)
				.filter(t -> "OPEN".equals(t.status()))
				.collectList();
	}

	public Mono<List<AlgoStrategyTrade>> loadClosedTrades(UUID runId) {
		return store.findTradesByRun(runId)
				.filter(t -> !"OPEN".equals(t.status()))
				.collectList();
	}
}

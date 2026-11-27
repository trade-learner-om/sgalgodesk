package com.sgalgodesk.desk.application.algo.port;

import java.util.List;
import java.util.UUID;

import com.sgalgodesk.desk.domain.algo.AlgoStrategy;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyEvent;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbound port: storage for algo strategies, runs, trades and events.
 * {@code insert*} creates a new row; {@code update*} / {@code save*} on an existing id updates it.
 */
public interface AlgoStrategyStorePort {

	// --- Strategy -------------------------------------------------------------

	Mono<AlgoStrategy> findStrategyById(UUID id);

	Flux<AlgoStrategy> findStrategiesByUser(UUID userId);

	Flux<AlgoStrategy> findStrategiesByStatus(String status);

	Mono<AlgoStrategy> insertStrategy(AlgoStrategy strategy);

	Mono<AlgoStrategy> updateStrategy(AlgoStrategy strategy);

	Mono<Void> deleteStrategy(UUID id);

	// --- Run ------------------------------------------------------------------

	Flux<AlgoStrategyRun> findRunsByStrategy(UUID strategyId);

	Mono<AlgoStrategyRun> findLatestRun(UUID strategyId);

	Flux<AlgoStrategyRun> findRunsByUser(UUID userId);

	Mono<AlgoStrategyRun> insertRun(AlgoStrategyRun run);

	Mono<AlgoStrategyRun> updateRun(AlgoStrategyRun run);

	/**
	 * Atomically set {@code lifecycle_state} only when it currently equals {@code fromState}.
	 * @return true if a row was updated
	 */
	Mono<Boolean> compareAndSetLifecycle(UUID runId, String fromState, String toState);

	// --- Trade ----------------------------------------------------------------

	Flux<AlgoStrategyTrade> findTradesByStrategy(UUID strategyId);

	Flux<AlgoStrategyTrade> findTradesByRun(UUID runId);

	Flux<AlgoStrategyTrade> findTradesByStrategyAndStatus(UUID strategyId, String status);

	Mono<AlgoStrategyTrade> insertTrade(AlgoStrategyTrade trade);

	Mono<AlgoStrategyTrade> updateTrade(AlgoStrategyTrade trade);

	/** Inserts the trades in order. */
	Mono<List<AlgoStrategyTrade>> insertTrades(List<AlgoStrategyTrade> trades);

	/** Updates the trades in order. */
	Mono<List<AlgoStrategyTrade>> updateTrades(List<AlgoStrategyTrade> trades);

	// --- Event ----------------------------------------------------------------

	Flux<AlgoStrategyEvent> findEventsByStrategy(UUID strategyId);

	Flux<AlgoStrategyEvent> findEventsByRun(UUID runId);

	Mono<AlgoStrategyEvent> insertEvent(AlgoStrategyEvent event);
}

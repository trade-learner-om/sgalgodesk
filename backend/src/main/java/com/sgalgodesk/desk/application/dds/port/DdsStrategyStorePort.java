package com.sgalgodesk.desk.application.dds.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sgalgodesk.desk.domain.dds.DdsEvent;
import com.sgalgodesk.desk.domain.dds.DdsRun;
import com.sgalgodesk.desk.domain.dds.DdsStrategy;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DdsStrategyStorePort {

	Mono<DdsStrategy> findStrategyById(UUID id);

	Flux<DdsStrategy> findStrategiesByUser(UUID userId);

	Flux<DdsStrategy> findStrategiesByStatus(String status);

	Mono<DdsStrategy> insertStrategy(DdsStrategy strategy);

	Mono<DdsStrategy> updateStrategy(DdsStrategy strategy);

	Mono<Void> deleteStrategy(UUID id);

	Flux<DdsRun> findRunsByStrategy(UUID strategyId);

	Mono<DdsRun> findLatestRun(UUID strategyId);

	Mono<DdsRun> insertRun(DdsRun run);

	Mono<DdsRun> updateRun(DdsRun run);

	Mono<Boolean> compareAndSetLifecycle(UUID runId, String fromState, String toState);

	Flux<DdsTrade> findTradesByStrategy(UUID strategyId);

	Flux<DdsTrade> findTradesByRun(UUID runId);

	Flux<DdsTrade> findTradesByStrategyAndStatus(UUID strategyId, String status);

	Flux<DdsTrade> findTradesByStrategyAndExecutedAtRange(
			UUID strategyId, Instant fromInclusive, Instant toExclusive);

	Mono<DdsTrade> insertTrade(DdsTrade trade);

	Mono<DdsTrade> updateTrade(DdsTrade trade);

	Mono<List<DdsTrade>> insertTrades(List<DdsTrade> trades);

	Mono<List<DdsTrade>> updateTrades(List<DdsTrade> trades);

	Flux<DdsEvent> findEventsByStrategy(UUID strategyId);

	Flux<DdsEvent> findEventsByRun(UUID runId);

	Mono<DdsEvent> insertEvent(DdsEvent event);
}

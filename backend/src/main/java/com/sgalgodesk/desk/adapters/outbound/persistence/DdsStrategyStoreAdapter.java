package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.DdsPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.DdsEventEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.DdsRunEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.DdsStrategyEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.DdsTradeEntityRepository;
import com.sgalgodesk.desk.application.dds.port.DdsStrategyStorePort;
import com.sgalgodesk.desk.domain.dds.DdsEvent;
import com.sgalgodesk.desk.domain.dds.DdsRun;
import com.sgalgodesk.desk.domain.dds.DdsStrategy;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class DdsStrategyStoreAdapter implements DdsStrategyStorePort {

	private final DdsStrategyEntityRepository strategies;
	private final DdsRunEntityRepository runs;
	private final DdsTradeEntityRepository trades;
	private final DdsEventEntityRepository events;

	@Override
	public Mono<DdsStrategy> findStrategyById(UUID id) {
		return strategies.findById(id).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsStrategy> findStrategiesByUser(UUID userId) {
		return strategies.findByUserIdOrderByUpdatedAtDesc(userId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsStrategy> findStrategiesByStatus(String status) {
		return strategies.findByStatus(status).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsStrategy> insertStrategy(DdsStrategy strategy) {
		return strategies.save(DdsPersistenceMapper.toEntity(strategy, true)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsStrategy> updateStrategy(DdsStrategy strategy) {
		return strategies.save(DdsPersistenceMapper.toEntity(strategy, false)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Void> deleteStrategy(UUID id) {
		return strategies.deleteById(id);
	}

	@Override
	public Flux<DdsRun> findRunsByStrategy(UUID strategyId) {
		return runs.findByStrategyIdOrderByStartedAtDesc(strategyId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsRun> findLatestRun(UUID strategyId) {
		return runs.findFirstByStrategyIdOrderByStartedAtDesc(strategyId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsRun> insertRun(DdsRun run) {
		return runs.save(DdsPersistenceMapper.toEntity(run, true)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsRun> updateRun(DdsRun run) {
		return runs.save(DdsPersistenceMapper.toEntity(run, false)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Boolean> compareAndSetLifecycle(UUID runId, String fromState, String toState) {
		return runs.updateLifecycleIf(runId, fromState, toState).map(n -> n != null && n > 0);
	}

	@Override
	public Flux<DdsTrade> findTradesByStrategy(UUID strategyId) {
		return trades.findByStrategyIdOrderByExecutedAtDesc(strategyId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsTrade> findTradesByRun(UUID runId) {
		return trades.findByRunIdOrderByExecutedAtAsc(runId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsTrade> findTradesByStrategyAndStatus(UUID strategyId, String status) {
		return trades.findByStrategyIdAndStatus(strategyId, status).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsTrade> findTradesByStrategyAndExecutedAtRange(
			UUID strategyId, Instant fromInclusive, Instant toExclusive) {
		return trades.findByStrategyIdAndExecutedAtRange(strategyId, fromInclusive, toExclusive)
				.map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsTrade> insertTrade(DdsTrade trade) {
		return trades.save(DdsPersistenceMapper.toEntity(trade, true)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsTrade> updateTrade(DdsTrade trade) {
		return trades.save(DdsPersistenceMapper.toEntity(trade, false)).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<List<DdsTrade>> insertTrades(List<DdsTrade> list) {
		return Flux.fromIterable(list).concatMap(this::insertTrade).collectList();
	}

	@Override
	public Mono<List<DdsTrade>> updateTrades(List<DdsTrade> list) {
		return Flux.fromIterable(list).concatMap(this::updateTrade).collectList();
	}

	@Override
	public Flux<DdsEvent> findEventsByStrategy(UUID strategyId) {
		return events.findByStrategyIdOrderByCreatedAtDesc(strategyId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Flux<DdsEvent> findEventsByRun(UUID runId) {
		return events.findByRunIdOrderByCreatedAtDesc(runId).map(DdsPersistenceMapper::toDomain);
	}

	@Override
	public Mono<DdsEvent> insertEvent(DdsEvent event) {
		return events.save(DdsPersistenceMapper.toEntity(event, true)).map(DdsPersistenceMapper::toDomain);
	}
}

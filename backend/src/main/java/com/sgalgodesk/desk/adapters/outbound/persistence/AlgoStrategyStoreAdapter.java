package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.AlgoPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.AlgoStrategyEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.AlgoStrategyEventEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.AlgoStrategyRunEntityRepository;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.AlgoStrategyTradeEntityRepository;
import com.sgalgodesk.desk.application.algo.port.AlgoStrategyStorePort;
import com.sgalgodesk.desk.domain.algo.AlgoStrategy;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyEvent;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AlgoStrategyStoreAdapter implements AlgoStrategyStorePort {

	private final AlgoStrategyEntityRepository strategies;
	private final AlgoStrategyRunEntityRepository runs;
	private final AlgoStrategyTradeEntityRepository trades;
	private final AlgoStrategyEventEntityRepository events;

	// --- Strategy -------------------------------------------------------------

	@Override
	public Mono<AlgoStrategy> findStrategyById(UUID id) {
		return strategies.findById(id).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategy> findStrategiesByUser(UUID userId) {
		return strategies.findByUserIdOrderByUpdatedAtDesc(userId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategy> findStrategiesByStatus(String status) {
		return strategies.findByStatus(status).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategy> insertStrategy(AlgoStrategy strategy) {
		return strategies.save(AlgoPersistenceMapper.toEntity(strategy, true)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategy> updateStrategy(AlgoStrategy strategy) {
		return strategies.save(AlgoPersistenceMapper.toEntity(strategy, false)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Void> deleteStrategy(UUID id) {
		return strategies.deleteById(id);
	}

	// --- Run ------------------------------------------------------------------

	@Override
	public Flux<AlgoStrategyRun> findRunsByStrategy(UUID strategyId) {
		return runs.findByStrategyIdOrderByStartedAtDesc(strategyId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyRun> findLatestRun(UUID strategyId) {
		return runs.findFirstByStrategyIdOrderByStartedAtDesc(strategyId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategyRun> findRunsByUser(UUID userId) {
		return runs.findByUserIdOrderByStartedAtDesc(userId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyRun> insertRun(AlgoStrategyRun run) {
		return runs.save(AlgoPersistenceMapper.toEntity(run, true)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyRun> updateRun(AlgoStrategyRun run) {
		return runs.save(AlgoPersistenceMapper.toEntity(run, false)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Boolean> compareAndSetLifecycle(UUID runId, String fromState, String toState) {
		return runs.updateLifecycleIf(runId, fromState, toState).map(updated -> updated != null && updated > 0);
	}

	// --- Trade ----------------------------------------------------------------

	@Override
	public Flux<AlgoStrategyTrade> findTradesByStrategy(UUID strategyId) {
		return trades.findByStrategyIdOrderByExecutedAtDesc(strategyId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategyTrade> findTradesByRun(UUID runId) {
		return trades.findByRunIdOrderByLegIndexAscExecutedAtAsc(runId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategyTrade> findTradesByStrategyAndStatus(UUID strategyId, String status) {
		return trades.findByStrategyIdAndStatus(strategyId, status).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyTrade> insertTrade(AlgoStrategyTrade trade) {
		return trades.save(AlgoPersistenceMapper.toEntity(trade, true)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyTrade> updateTrade(AlgoStrategyTrade trade) {
		return trades.save(AlgoPersistenceMapper.toEntity(trade, false)).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<List<AlgoStrategyTrade>> insertTrades(List<AlgoStrategyTrade> list) {
		return Flux.fromIterable(list).concatMap(this::insertTrade).collectList();
	}

	@Override
	public Mono<List<AlgoStrategyTrade>> updateTrades(List<AlgoStrategyTrade> list) {
		return Flux.fromIterable(list).concatMap(this::updateTrade).collectList();
	}

	// --- Event ----------------------------------------------------------------

	@Override
	public Flux<AlgoStrategyEvent> findEventsByStrategy(UUID strategyId) {
		return events.findByStrategyIdOrderByCreatedAtDesc(strategyId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Flux<AlgoStrategyEvent> findEventsByRun(UUID runId) {
		return events.findByRunIdOrderByCreatedAtDesc(runId).map(AlgoPersistenceMapper::toDomain);
	}

	@Override
	public Mono<AlgoStrategyEvent> insertEvent(AlgoStrategyEvent event) {
		return events.save(AlgoPersistenceMapper.toEntity(event, true)).map(AlgoPersistenceMapper::toDomain);
	}
}

package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyRunEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlgoStrategyRunEntityRepository extends ReactiveCrudRepository<AlgoStrategyRunEntity, UUID> {

	Flux<AlgoStrategyRunEntity> findByStrategyIdOrderByStartedAtDesc(UUID strategyId);

	Mono<AlgoStrategyRunEntity> findFirstByStrategyIdOrderByStartedAtDesc(UUID strategyId);

	Flux<AlgoStrategyRunEntity> findByUserIdOrderByStartedAtDesc(UUID userId);

	@Modifying
	@Query("""
			UPDATE algo_strategy_run
			SET lifecycle_state = :toState
			WHERE id = :id AND lifecycle_state = :fromState
			""")
	Mono<Long> updateLifecycleIf(UUID id, String fromState, String toState);
}

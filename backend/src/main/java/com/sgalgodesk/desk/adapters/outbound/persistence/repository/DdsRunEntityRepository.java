package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsRunEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DdsRunEntityRepository extends ReactiveCrudRepository<DdsRunEntity, UUID> {

	Flux<DdsRunEntity> findByStrategyIdOrderByStartedAtDesc(UUID strategyId);

	Mono<DdsRunEntity> findFirstByStrategyIdOrderByStartedAtDesc(UUID strategyId);

	@Modifying
	@Query("""
			UPDATE dds_run
			SET lifecycle_state = :toState
			WHERE id = :id AND lifecycle_state = :fromState
			""")
	Mono<Long> updateLifecycleIf(UUID id, String fromState, String toState);
}

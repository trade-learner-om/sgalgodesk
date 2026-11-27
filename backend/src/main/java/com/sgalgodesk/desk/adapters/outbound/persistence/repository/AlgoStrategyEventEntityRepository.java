package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyEventEntity;

import reactor.core.publisher.Flux;

public interface AlgoStrategyEventEntityRepository extends ReactiveCrudRepository<AlgoStrategyEventEntity, UUID> {

	Flux<AlgoStrategyEventEntity> findByStrategyIdOrderByCreatedAtDesc(UUID strategyId);

	Flux<AlgoStrategyEventEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);
}

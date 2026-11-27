package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsEventEntity;

import reactor.core.publisher.Flux;

public interface DdsEventEntityRepository extends ReactiveCrudRepository<DdsEventEntity, UUID> {

	Flux<DdsEventEntity> findByStrategyIdOrderByCreatedAtDesc(UUID strategyId);

	Flux<DdsEventEntity> findByRunIdOrderByCreatedAtDesc(UUID runId);
}

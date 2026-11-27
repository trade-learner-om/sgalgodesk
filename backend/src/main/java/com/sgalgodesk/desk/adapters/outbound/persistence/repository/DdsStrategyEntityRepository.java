package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsStrategyEntity;

import reactor.core.publisher.Flux;

public interface DdsStrategyEntityRepository extends ReactiveCrudRepository<DdsStrategyEntity, UUID> {

	Flux<DdsStrategyEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

	Flux<DdsStrategyEntity> findByStatus(String status);
}

package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyEntity;

import reactor.core.publisher.Flux;

public interface AlgoStrategyEntityRepository extends ReactiveCrudRepository<AlgoStrategyEntity, UUID> {

	Flux<AlgoStrategyEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

	Flux<AlgoStrategyEntity> findByStatus(String status);
}

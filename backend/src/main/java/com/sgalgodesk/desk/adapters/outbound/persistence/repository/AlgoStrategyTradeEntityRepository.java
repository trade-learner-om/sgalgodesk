package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AlgoStrategyTradeEntity;

import reactor.core.publisher.Flux;

public interface AlgoStrategyTradeEntityRepository extends ReactiveCrudRepository<AlgoStrategyTradeEntity, UUID> {

	Flux<AlgoStrategyTradeEntity> findByStrategyIdOrderByExecutedAtDesc(UUID strategyId);

	Flux<AlgoStrategyTradeEntity> findByRunIdOrderByLegIndexAscExecutedAtAsc(UUID runId);

	Flux<AlgoStrategyTradeEntity> findByStrategyIdAndStatus(UUID strategyId, String status);
}

package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.DdsTradeEntity;

import reactor.core.publisher.Flux;

public interface DdsTradeEntityRepository extends ReactiveCrudRepository<DdsTradeEntity, UUID> {

	Flux<DdsTradeEntity> findByStrategyIdOrderByExecutedAtDesc(UUID strategyId);

	Flux<DdsTradeEntity> findByRunIdOrderByExecutedAtAsc(UUID runId);

	Flux<DdsTradeEntity> findByStrategyIdAndStatus(UUID strategyId, String status);

	@Query("""
			SELECT * FROM dds_trade
			WHERE strategy_id = :strategyId
			  AND executed_at >= :fromInclusive
			  AND executed_at < :toExclusive
			ORDER BY executed_at DESC
			""")
	Flux<DdsTradeEntity> findByStrategyIdAndExecutedAtRange(
			UUID strategyId, Instant fromInclusive, Instant toExclusive);
}

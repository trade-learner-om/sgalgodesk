package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerSessionEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BrokerSessionEntityRepository extends ReactiveCrudRepository<BrokerSessionEntity, UUID> {

	Mono<BrokerSessionEntity> findByUserIdAndBroker(UUID userId, String broker);

	Flux<BrokerSessionEntity> findByUserId(UUID userId);

	Mono<Void> deleteByUserIdAndBroker(UUID userId, String broker);
}

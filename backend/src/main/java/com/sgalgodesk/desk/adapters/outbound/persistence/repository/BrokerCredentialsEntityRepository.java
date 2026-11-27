package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerCredentialsEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BrokerCredentialsEntityRepository extends ReactiveCrudRepository<BrokerCredentialsEntity, UUID> {

	Mono<BrokerCredentialsEntity> findByUserIdAndBroker(UUID userId, String broker);

	Mono<Boolean> existsByUserIdAndBroker(UUID userId, String broker);

	Flux<BrokerCredentialsEntity> findByUserId(UUID userId);

	Mono<Void> deleteByUserIdAndBroker(UUID userId, String broker);
}

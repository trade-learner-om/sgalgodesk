package com.sgalgodesk.desk.application.broker.port;

import java.util.UUID;

import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Outbound port: persistence of broker API credentials. */
public interface BrokerCredentialsPort {

	Mono<BrokerCredentials> findByUserIdAndBroker(UUID userId, BrokerId broker);

	Mono<Boolean> existsByUserIdAndBroker(UUID userId, BrokerId broker);

	Flux<BrokerCredentials> findByUserId(UUID userId);

	/** Inserts or updates credentials for (userId, broker). */
	Mono<BrokerCredentials> save(BrokerCredentials credentials);

	Mono<Void> deleteByUserIdAndBroker(UUID userId, BrokerId broker);
}

package com.sgalgodesk.desk.application.broker.port;

import java.util.UUID;

import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Outbound port: persistence of broker login sessions. */
public interface BrokerSessionPort {

	Mono<BrokerSession> findByUserIdAndBroker(UUID userId, BrokerId broker);

	Flux<BrokerSession> findByUserId(UUID userId);

	/** Inserts or updates the session for (userId, broker). */
	Mono<BrokerSession> save(BrokerSession session);

	Mono<Void> deleteByUserIdAndBroker(UUID userId, BrokerId broker);
}

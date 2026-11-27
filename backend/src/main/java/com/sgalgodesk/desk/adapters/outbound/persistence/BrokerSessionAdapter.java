package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerSessionEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.BrokerPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.BrokerSessionEntityRepository;
import com.sgalgodesk.desk.application.broker.port.BrokerSessionPort;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BrokerSessionAdapter implements BrokerSessionPort {

	private final BrokerSessionEntityRepository repository;

	@Override
	public Mono<BrokerSession> findByUserIdAndBroker(UUID userId, BrokerId broker) {
		return repository.findByUserIdAndBroker(userId, broker.code()).map(BrokerPersistenceMapper::toDomain);
	}

	@Override
	public Flux<BrokerSession> findByUserId(UUID userId) {
		return repository.findByUserId(userId).map(BrokerPersistenceMapper::toDomain);
	}

	@Override
	public Mono<BrokerSession> save(BrokerSession session) {
		return repository.findByUserIdAndBroker(session.userId(), session.broker().code())
				.map(existing -> {
					UUID existingId = existing.getId();
					BrokerSessionEntity entity = BrokerPersistenceMapper.toEntity(session, existing);
					entity.setId(existingId);
					entity.markPersisted();
					return entity;
				})
				.switchIfEmpty(Mono.fromSupplier(() -> {
					BrokerSessionEntity entity = BrokerPersistenceMapper.toEntity(session);
					if (entity.getId() == null) {
						entity.setId(UUID.randomUUID());
					}
					return entity;
				}))
				.flatMap(repository::save)
				.map(BrokerPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Void> deleteByUserIdAndBroker(UUID userId, BrokerId broker) {
		return repository.deleteByUserIdAndBroker(userId, broker.code());
	}
}

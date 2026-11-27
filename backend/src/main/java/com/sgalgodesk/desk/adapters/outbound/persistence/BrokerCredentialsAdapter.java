package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerCredentialsEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.BrokerPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.BrokerCredentialsEntityRepository;
import com.sgalgodesk.desk.application.broker.port.BrokerCredentialsPort;
import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BrokerCredentialsAdapter implements BrokerCredentialsPort {

	private final BrokerCredentialsEntityRepository repository;

	@Override
	public Mono<BrokerCredentials> findByUserIdAndBroker(UUID userId, BrokerId broker) {
		return repository.findByUserIdAndBroker(userId, broker.code()).map(BrokerPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Boolean> existsByUserIdAndBroker(UUID userId, BrokerId broker) {
		return repository.existsByUserIdAndBroker(userId, broker.code());
	}

	@Override
	public Flux<BrokerCredentials> findByUserId(UUID userId) {
		return repository.findByUserId(userId).map(BrokerPersistenceMapper::toDomain);
	}

	@Override
	public Mono<BrokerCredentials> save(BrokerCredentials credentials) {
		return repository.findByUserIdAndBroker(credentials.userId(), credentials.broker().code())
				.map(existing -> {
					UUID existingId = existing.getId();
					BrokerCredentialsEntity entity = BrokerPersistenceMapper.toEntity(credentials, existing);
					entity.setId(existingId);
					entity.markPersisted();
					return entity;
				})
				.switchIfEmpty(Mono.fromSupplier(() -> {
					BrokerCredentialsEntity entity = BrokerPersistenceMapper.toEntity(credentials);
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

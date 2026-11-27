package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.UserSettingsEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.AccountPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.UserSettingsEntityRepository;
import com.sgalgodesk.desk.application.account.port.UserSettingsPort;
import com.sgalgodesk.desk.domain.account.UserSettings;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class UserSettingsAdapter implements UserSettingsPort {

	private final UserSettingsEntityRepository repository;

	@Override
	public Mono<UserSettings> findByUserId(UUID userId) {
		return repository.findById(userId).map(AccountPersistenceMapper::toDomain);
	}

	@Override
	public Mono<UserSettings> save(UserSettings settings) {
		UserSettingsEntity entity = AccountPersistenceMapper.toEntity(settings);
		return repository.existsById(settings.userId())
				.flatMap(exists -> {
					if (exists) {
						entity.markPersisted();
					} else {
						entity.markNew();
					}
					return repository.save(entity);
				})
				.map(AccountPersistenceMapper::toDomain);
	}
}

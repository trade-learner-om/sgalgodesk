package com.sgalgodesk.desk.adapters.outbound.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.persistence.mapper.AccountPersistenceMapper;
import com.sgalgodesk.desk.adapters.outbound.persistence.repository.AppUserEntityRepository;
import com.sgalgodesk.desk.application.account.port.UserRepositoryPort;
import com.sgalgodesk.desk.domain.account.User;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepositoryPort {

	private final AppUserEntityRepository repository;

	@Override
	public Mono<User> findById(UUID id) {
		return repository.findById(id).map(AccountPersistenceMapper::toDomain);
	}

	@Override
	public Mono<User> findByEmail(String email) {
		return repository.findByEmailIgnoreCase(email).map(AccountPersistenceMapper::toDomain);
	}

	@Override
	public Mono<Boolean> existsByEmail(String email) {
		return repository.existsByEmailIgnoreCase(email);
	}

	@Override
	public Mono<User> save(User user) {
		return repository.save(AccountPersistenceMapper.toEntity(user)).map(AccountPersistenceMapper::toDomain);
	}
}

package com.sgalgodesk.desk.application.account.port;

import java.util.UUID;

import com.sgalgodesk.desk.domain.account.User;

import reactor.core.publisher.Mono;

/** Outbound port: persistence of application users. */
public interface UserRepositoryPort {

	Mono<User> findById(UUID id);

	Mono<User> findByEmail(String email);

	Mono<Boolean> existsByEmail(String email);

	/** Inserts a new user. */
	Mono<User> save(User user);
}

package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AppUserEntity;

import reactor.core.publisher.Mono;

public interface AppUserEntityRepository extends ReactiveCrudRepository<AppUserEntity, UUID> {

	Mono<AppUserEntity> findByEmailIgnoreCase(String email);

	Mono<Boolean> existsByEmailIgnoreCase(String email);
}

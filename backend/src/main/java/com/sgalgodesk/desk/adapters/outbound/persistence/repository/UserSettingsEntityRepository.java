package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.UserSettingsEntity;

public interface UserSettingsEntityRepository extends ReactiveCrudRepository<UserSettingsEntity, UUID> {
}

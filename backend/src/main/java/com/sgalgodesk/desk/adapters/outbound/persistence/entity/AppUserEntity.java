package com.sgalgodesk.desk.adapters.outbound.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUserEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	private String email;

	@Column("password_hash")
	private String passwordHash;

	@Column("created_at")
	private Instant createdAt;

	@Transient
	private boolean newEntity;

	public AppUserEntity(UUID id, String email, String passwordHash, Instant createdAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
		this.newEntity = true;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	public void markPersisted() {
		this.newEntity = false;
	}
}

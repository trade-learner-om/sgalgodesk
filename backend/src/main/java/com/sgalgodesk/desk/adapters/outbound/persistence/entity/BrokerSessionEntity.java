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

@Table("broker_session")
@Getter
@Setter
@NoArgsConstructor
public class BrokerSessionEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("user_id")
	private UUID userId;

	@Column("broker")
	private String broker;

	@Column("broker_user_id")
	private String brokerUserId;

	@Column("display_name")
	private String displayName;

	@Column("access_token")
	private String accessToken;

	@Column("refresh_meta")
	private String refreshMeta;

	@Column("expires_at")
	private Instant expiresAt;

	@Column("updated_at")
	private Instant updatedAt;

	@Transient
	private boolean newEntity;

	@Override
	public UUID getId() {
		return id;
	}

	public boolean isValid(Instant now) {
		return accessToken != null && expiresAt != null && now.isBefore(expiresAt);
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	public void markPersisted() {
		this.newEntity = false;
	}

	public void markNew() {
		this.newEntity = true;
	}
}

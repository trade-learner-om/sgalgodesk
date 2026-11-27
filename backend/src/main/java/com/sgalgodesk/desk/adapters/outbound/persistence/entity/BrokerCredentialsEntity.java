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

@Table("broker_credentials")
@Getter
@Setter
@NoArgsConstructor
public class BrokerCredentialsEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("user_id")
	private UUID userId;

	@Column("broker")
	private String broker;

	@Column("client_id")
	private String clientId;

	@Column("client_secret")
	private String clientSecret;

	@Column("extra_json")
	private String extraJson;

	@Column("updated_at")
	private Instant updatedAt;

	@Transient
	private boolean newEntity;

	@Override
	public UUID getId() {
		return id;
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

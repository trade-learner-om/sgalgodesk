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

@Table("user_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserSettingsEntity implements Persistable<UUID> {

	@Id
	@Column("user_id")
	private UUID userId;

	@Column("preferred_underlying_key")
	private String preferredUnderlyingKey;

	@Column("preferred_underlying_symbol")
	private String preferredUnderlyingSymbol;

	@Column("preferred_asset_class")
	private String preferredAssetClass;

	@Column("active_broker")
	private String activeBroker;

	@Column("updated_at")
	private Instant updatedAt;

	@Transient
	private boolean newEntity;

	@Override
	public UUID getId() {
		return userId;
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

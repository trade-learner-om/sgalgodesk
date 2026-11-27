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

@Table("algo_strategy")
@Getter
@Setter
@NoArgsConstructor
public class AlgoStrategyEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("user_id")
	private UUID userId;

	private String name;

	private String status;

	@Column("execution_mode")
	private String executionMode;

	@Column("config_json")
	private String configJson;

	@Column("created_at")
	private Instant createdAt;

	@Column("updated_at")
	private Instant updatedAt;

	@Transient
	private boolean newEntity;

	@Override
	public boolean isNew() {
		return newEntity;
	}

	public void markNew() {
		this.newEntity = true;
	}

	public void markPersisted() {
		this.newEntity = false;
	}
}

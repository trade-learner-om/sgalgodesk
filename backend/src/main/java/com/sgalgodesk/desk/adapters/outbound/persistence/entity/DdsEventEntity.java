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

@Table("dds_event")
@Getter
@Setter
@NoArgsConstructor
public class DdsEventEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("strategy_id")
	private UUID strategyId;

	@Column("run_id")
	private UUID runId;

	@Column("user_id")
	private UUID userId;

	@Column("event_type")
	private String eventType;

	private String message;

	@Column("created_at")
	private Instant createdAt;

	@Transient
	private boolean newEntity;

	@Override
	public boolean isNew() {
		return newEntity;
	}
}

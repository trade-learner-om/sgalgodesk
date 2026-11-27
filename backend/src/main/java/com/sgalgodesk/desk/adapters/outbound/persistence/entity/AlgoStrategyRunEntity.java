package com.sgalgodesk.desk.adapters.outbound.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("algo_strategy_run")
@Getter
@Setter
@NoArgsConstructor
public class AlgoStrategyRunEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("strategy_id")
	private UUID strategyId;

	@Column("user_id")
	private UUID userId;

	@Column("run_date")
	private LocalDate runDate;

	@Column("execution_mode")
	private String executionMode;

	@Column("lifecycle_state")
	private String lifecycleState;

	@Column("realised_pnl")
	private double realisedPnl;

	@Column("unrealised_pnl")
	private double unrealisedPnl;

	@Column("reentry_count")
	private int reentryCount;

	@Column("trail_floor_pnl")
	private Double trailFloorPnl;

	@Column("peak_pnl")
	private double peakPnl;

	@Column("started_at")
	private Instant startedAt;

	@Column("ended_at")
	private Instant endedAt;

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

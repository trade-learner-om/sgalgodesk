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

@Table("dds_trade")
@Getter
@Setter
@NoArgsConstructor
public class DdsTradeEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column("strategy_id")
	private UUID strategyId;

	@Column("run_id")
	private UUID runId;

	@Column("user_id")
	private UUID userId;

	@Column("leg_role")
	private String legRole;

	@Column("instrument_key")
	private String instrumentKey;

	@Column("trading_symbol")
	private String tradingSymbol;

	private String side;

	private int quantity;

	@Column("execution_mode")
	private String executionMode;

	@Column("broker_order_id")
	private String brokerOrderId;

	@Column("entry_price")
	private double entryPrice;

	@Column("exit_price")
	private Double exitPrice;

	private double pnl;

	@Column("trigger_reason")
	private String triggerReason;

	private String status;

	@Column("delta_at_entry")
	private Double deltaAtEntry;

	@Column("lot_size")
	private Integer lotSize;

	private Double strike;

	private String expiry;

	@Column("option_type")
	private String optionType;

	@Column("executed_at")
	private Instant executedAt;

	@Column("closed_at")
	private Instant closedAt;

	@Transient
	private boolean newEntity;

	@Override
	public boolean isNew() {
		return newEntity;
	}
}

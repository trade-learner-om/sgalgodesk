package com.sgalgodesk.desk.domain.dds;

import java.time.Instant;
import java.util.UUID;

public record DdsTrade(
		UUID id,
		UUID strategyId,
		UUID runId,
		UUID userId,
		String legRole,
		String instrumentKey,
		String tradingSymbol,
		String side,
		int quantity,
		String executionMode,
		String brokerOrderId,
		double entryPrice,
		Double exitPrice,
		double pnl,
		String triggerReason,
		String status,
		Double deltaAtEntry,
		Integer lotSize,
		Double strike,
		String expiry,
		String optionType,
		Instant executedAt,
		Instant closedAt) {

	public DdsTrade closed(
			String newStatus, Double newExitPrice, double newPnl, String newTriggerReason, Instant now) {
		return new DdsTrade(id, strategyId, runId, userId, legRole, instrumentKey, tradingSymbol, side, quantity,
				executionMode, brokerOrderId, entryPrice, newExitPrice, newPnl, newTriggerReason, newStatus,
				deltaAtEntry, lotSize, strike, expiry, optionType, executedAt, now);
	}
}

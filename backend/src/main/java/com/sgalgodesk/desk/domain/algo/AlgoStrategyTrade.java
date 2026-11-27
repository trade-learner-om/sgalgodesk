package com.sgalgodesk.desk.domain.algo;

import java.time.Instant;
import java.util.UUID;

public record AlgoStrategyTrade(
		UUID id,
		UUID strategyId,
		UUID runId,
		UUID userId,
		int legIndex,
		String instrumentKey,
		String tradingSymbol,
		String side,
		int quantity,
		String executionMode,
		String brokerOrderId,
		String slOrderId,
		Double slTriggerPrice,
		double entryPrice,
		Double exitPrice,
		double pnl,
		String triggerReason,
		String status,
		Instant executedAt,
		Instant closedAt,
		Integer lotSize,
		Double strike,
		String expiry,
		String optionType) {


	public AlgoStrategyTrade closed(
			String newStatus, Double newExitPrice, double newPnl, String newTriggerReason, Instant now) {
		return new AlgoStrategyTrade(id, strategyId, runId, userId, legIndex, instrumentKey, tradingSymbol, side,
				quantity, executionMode, brokerOrderId, slOrderId, slTriggerPrice, entryPrice, newExitPrice, newPnl,
				newTriggerReason, newStatus, executedAt, now, lotSize, strike, expiry, optionType);
	}

	public AlgoStrategyTrade withSl(String newSlOrderId, Double newSlTriggerPrice) {
		return new AlgoStrategyTrade(id, strategyId, runId, userId, legIndex, instrumentKey, tradingSymbol, side,
				quantity, executionMode, brokerOrderId, newSlOrderId, newSlTriggerPrice, entryPrice, exitPrice, pnl,
				triggerReason, status, executedAt, closedAt, lotSize, strike, expiry, optionType);
	}
}

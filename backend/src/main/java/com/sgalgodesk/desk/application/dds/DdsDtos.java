package com.sgalgodesk.desk.application.dds;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class DdsDtos {

	private DdsDtos() {
	}

	public record DdsSettings(
			@Min(1) @Max(50) double initialDelta,
			@NotBlank String maxLossType,
			@Min(0) double maxLossValue,
			@NotBlank String exitTime,
			@Min(1) int lots) {

		public static DdsSettings defaults() {
			return new DdsSettings(25, "POINTS", 50, "15:00", 1);
		}
	}

	public record SaveDdsStrategyRequest(
			@NotBlank String name,
			String executionMode,
			@NotNull @Valid DdsSettings settings) {
	}

	public record RunDdsStrategyRequest(
			@NotBlank String executionMode) {
	}

	public record TradeDto(
			UUID id,
			UUID strategyId,
			UUID runId,
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
	}

	public record RunDto(
			UUID id,
			UUID strategyId,
			LocalDate runDate,
			String executionMode,
			String lifecycleState,
			double realisedPnl,
			double unrealisedPnl,
			Instant startedAt,
			Instant endedAt) {
	}

	public record EventDto(
			UUID id,
			UUID strategyId,
			UUID runId,
			String eventType,
			String message,
			Instant createdAt) {
	}

	public record StrategySummaryResponse(
			UUID id,
			String name,
			String status,
			String executionMode,
			DdsSettings settings,
			RunDto activeRun,
			List<TradeDto> activeTrades,
			int totalTrades,
			int tradesToday,
			double totalPnl,
			double todayPnl,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record StrategyLifecycleResponse(
			StrategySummaryResponse strategy,
			List<RunDto> runs,
			List<TradeDto> trades,
			List<EventDto> events) {
	}

	public record HistoryDayGroup(
			LocalDate date,
			List<TradeDto> trades,
			double dayPnl) {
	}

	public record HistoryResponse(
			List<HistoryDayGroup> groups,
			String nextCursor,
			boolean hasMore) {
	}
}

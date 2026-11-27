package com.sgalgodesk.desk.application.algo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class AlgoStrategyDtos {

	private AlgoStrategyDtos() {
	}

	public record StrategyConfig(
			@NotNull @Valid InstrumentSettings instrument,
			@NotNull @Valid EntrySettings entry,
			@NotNull @Valid LegwiseSettings legwise,
			@NotEmpty @Valid List<LegConfig> legs,
			@NotNull @Valid OverallSettings overall) {
	}

	public record InstrumentSettings(
			@NotBlank String index,
			@NotBlank String underlyingFrom) {
	}

	public record EntrySettings(
			@NotBlank String strategyType,
			@NotBlank String entryTime,
			@NotBlank String exitTime,
			boolean noReentryAfterEnabled,
			String noReentryAfterTime,
			boolean stopMonitoringAfterEnabled,
			String stopMonitoringAfterTime) {
	}

	public record LegwiseSettings(
			@NotBlank String squareOff,
			boolean trailSlToBreakeven,
			String trailSlBreakevenScope) {
	}

	public record LegConfig(
			String segment,
			int lots,
			String position,
			String optionType,
			String expiry,
			String strikeCriteria,
			String strikeType,
			Double strikeParam1,
			Double strikeParam2,
			boolean targetEnabled,
			String targetUnit,
			Double targetValue,
			boolean slEnabled,
			String slUnit,
			Double slValue,
			boolean trailSlEnabled,
			String trailSlUnit,
			Double trailSlTrigger,
			Double trailSlMove,
			boolean reentryTgtEnabled,
			String reentryTgtMode,
			Integer reentryTgtCount,
			boolean reentrySlEnabled,
			String reentrySlMode,
			Integer reentrySlCount,
			boolean rangeBreakoutEnabled,
			String rangeBreakoutTime,
			String rangeBreakoutDirection,
			String rangeBreakoutOn,
			boolean simpleMomentumEnabled,
			String simpleMomentumUnit,
			Double simpleMomentumValue) {
	}

	public record OverallSettings(
			boolean overallSlEnabled,
			String overallSlType,
			Double overallSlValue,
			boolean overallTargetEnabled,
			String overallTargetType,
			Double overallTargetValue,
			boolean overallMomentumEnabled,
			String overallMomentumType,
			Double overallMomentumValue,
			boolean reentryOnSlEnabled,
			String reentryOnSlMode,
			Integer reentryOnSlCount,
			boolean reentryOnTgtEnabled,
			String reentryOnTgtMode,
			Integer reentryOnTgtCount,
			boolean trailingEnabled,
			String trailingMode,
			Double profitReaches,
			Double lockProfit,
			Double trailStepProfit,
			Double trailStepLock) {
	}

	public record SaveStrategyRequest(
			@NotBlank String name,
			String executionMode,
			@NotNull @Valid StrategyConfig config) {
	}

	public record RunStrategyRequest(
			@NotBlank String executionMode) {
	}

	public record StrategySummaryResponse(
			UUID id,
			String name,
			String status,
			String executionMode,
			StrategyConfig config,
			RunDto activeRun,
			List<TradeDto> activeTrades,
			int totalTrades,
			int tradesToday,
			double totalPnl,
			double todayPnl,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record RunDto(
			UUID id,
			UUID strategyId,
			LocalDate runDate,
			String executionMode,
			String lifecycleState,
			double realisedPnl,
			double unrealisedPnl,
			int reentryCount,
			Double trailFloorPnl,
			double peakPnl,
			Instant startedAt,
			Instant endedAt) {
	}

	public record TradeDto(
			UUID id,
			UUID strategyId,
			UUID runId,
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
	}

	public record EventDto(
			UUID id,
			UUID strategyId,
			UUID runId,
			String eventType,
			String message,
			Instant createdAt) {
	}

	public record StrategyLifecycleResponse(
			StrategySummaryResponse strategy,
			List<RunDto> runs,
			List<TradeDto> trades,
			List<EventDto> events) {
	}
}

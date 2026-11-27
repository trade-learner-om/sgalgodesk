package com.sgalgodesk.desk.domain.algo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AlgoStrategyRun(
		UUID id,
		UUID strategyId,
		UUID userId,
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


	public AlgoStrategyRun withLifecycleState(String state) {
		return new AlgoStrategyRun(id, strategyId, userId, runDate, executionMode, state, realisedPnl,
				unrealisedPnl, reentryCount, trailFloorPnl, peakPnl, startedAt, endedAt);
	}

	public AlgoStrategyRun stopped(double deltaRealised, Instant now) {
		return new AlgoStrategyRun(id, strategyId, userId, runDate, executionMode, "STOPPED",
				realisedPnl + deltaRealised, 0, reentryCount, trailFloorPnl, peakPnl, startedAt, now);
	}

	public AlgoStrategyRun withMtm(double newUnrealised, double newPeak, Double newTrailFloor) {
		return new AlgoStrategyRun(id, strategyId, userId, runDate, executionMode, lifecycleState, realisedPnl,
				newUnrealised, reentryCount, newTrailFloor, newPeak, startedAt, endedAt);
	}
}

package com.sgalgodesk.desk.domain.dds;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DdsRun(
		UUID id,
		UUID strategyId,
		UUID userId,
		LocalDate runDate,
		String executionMode,
		String lifecycleState,
		double realisedPnl,
		double unrealisedPnl,
		Instant startedAt,
		Instant endedAt) {

	public DdsRun withLifecycleState(String state) {
		return new DdsRun(id, strategyId, userId, runDate, executionMode, state, realisedPnl, unrealisedPnl,
				startedAt, endedAt);
	}

	public DdsRun stopped(double deltaRealised, Instant now) {
		return new DdsRun(id, strategyId, userId, runDate, executionMode, "STOPPED",
				realisedPnl + deltaRealised, 0, startedAt, now);
	}

	public DdsRun withMtm(double newUnrealised) {
		return new DdsRun(id, strategyId, userId, runDate, executionMode, lifecycleState, realisedPnl,
				newUnrealised, startedAt, endedAt);
	}
}

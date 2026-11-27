package com.sgalgodesk.desk.domain.algo;

import java.time.Instant;
import java.util.UUID;

public record AlgoStrategyEvent(
		UUID id,
		UUID strategyId,
		UUID runId,
		UUID userId,
		String eventType,
		String message,
		Instant createdAt) {
}

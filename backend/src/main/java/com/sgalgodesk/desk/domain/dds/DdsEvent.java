package com.sgalgodesk.desk.domain.dds;

import java.time.Instant;
import java.util.UUID;

public record DdsEvent(
		UUID id,
		UUID strategyId,
		UUID runId,
		UUID userId,
		String eventType,
		String message,
		Instant createdAt) {
}

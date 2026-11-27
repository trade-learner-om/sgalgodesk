package com.sgalgodesk.desk.domain.algo;

import java.time.Instant;
import java.util.UUID;

public record AlgoStrategy(
		UUID id,
		UUID userId,
		String name,
		String status,
		String executionMode,
		String configJson,
		Instant createdAt,
		Instant updatedAt) {


	public AlgoStrategy withStatus(String newStatus, Instant now) {
		return new AlgoStrategy(id, userId, name, newStatus, executionMode, configJson, createdAt, now);
	}

	public AlgoStrategy withRunning(String mode, Instant now) {
		return new AlgoStrategy(id, userId, name, "RUNNING", mode, configJson, createdAt, now);
	}

	public AlgoStrategy withDefinition(String newName, String newMode, String newConfigJson, Instant now) {
		return new AlgoStrategy(id, userId, newName, status, newMode, newConfigJson, createdAt, now);
	}

	public AlgoStrategy touched(Instant now) {
		return new AlgoStrategy(id, userId, name, status, executionMode, configJson, createdAt, now);
	}
}

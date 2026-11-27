package com.sgalgodesk.desk.domain.dds;

import java.time.Instant;
import java.util.UUID;

public record DdsStrategy(
		UUID id,
		UUID userId,
		String name,
		String status,
		String executionMode,
		String settingsJson,
		Instant createdAt,
		Instant updatedAt) {

	public DdsStrategy withStatus(String newStatus, Instant now) {
		return new DdsStrategy(id, userId, name, newStatus, executionMode, settingsJson, createdAt, now);
	}

	public DdsStrategy withRunning(String mode, Instant now) {
		return new DdsStrategy(id, userId, name, "RUNNING", mode, settingsJson, createdAt, now);
	}

	public DdsStrategy withDefinition(String newName, String newMode, String newSettingsJson, Instant now) {
		return new DdsStrategy(id, userId, newName, status, newMode, newSettingsJson, createdAt, now);
	}

	public DdsStrategy touched(Instant now) {
		return new DdsStrategy(id, userId, name, status, executionMode, settingsJson, createdAt, now);
	}
}

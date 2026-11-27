package com.sgalgodesk.desk.domain.account;

import java.time.Instant;
import java.util.UUID;

public record DeviceSession(
		UUID id,
		UUID userId,
		UUID deviceId,
		Instant expiresAt,
		Instant createdAt,
		Instant lastSeenAt) {

	public boolean isValid(Instant now) {
		return expiresAt != null && now.isBefore(expiresAt);
	}
}

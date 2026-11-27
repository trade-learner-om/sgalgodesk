package com.sgalgodesk.desk.domain.broker;

import java.time.Instant;
import java.util.UUID;

public record BrokerSession(
		UUID id,
		UUID userId,
		BrokerId broker,
		String accessToken,
		String refreshToken,
		String displayName,
		Instant expiresAt,
		Instant createdAt,
		Instant updatedAt) {

	public boolean isValid(Instant now) {
		return accessToken != null && expiresAt != null && now.isBefore(expiresAt);
	}
}

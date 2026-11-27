package com.sgalgodesk.desk.domain.broker;

import java.time.Instant;
import java.util.UUID;

public record BrokerCredentials(
		UUID id,
		UUID userId,
		BrokerId broker,
		String clientId,
		String clientSecret,
		Instant createdAt,
		Instant updatedAt) {
}

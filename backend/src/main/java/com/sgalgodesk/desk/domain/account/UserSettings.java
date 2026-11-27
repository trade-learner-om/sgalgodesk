package com.sgalgodesk.desk.domain.account;

import java.time.Instant;
import java.util.UUID;

import com.sgalgodesk.desk.domain.broker.BrokerId;

public record UserSettings(
		UUID userId,
		String instrumentKey,
		String tradingSymbol,
		String assetClass,
		BrokerId activeBroker,
		Instant updatedAt) {
}

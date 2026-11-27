package com.sgalgodesk.desk.domain.broker;

import java.util.Locale;

public enum BrokerId {
	MSTOCK;

	public static BrokerId from(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		if ("UPSTOX".equals(normalized)) {
			return null;
		}
		return BrokerId.valueOf(normalized);
	}

	public String code() {
		return name();
	}
}

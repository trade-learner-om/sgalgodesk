package com.sgalgodesk.desk.application.indicators;

import java.time.Instant;

/**
 * One Supertrend sample aligned with a candle timestamp.
 */
public record SupertrendPoint(
		Instant timestamp,
		double supertrend,
		TrendDirection direction,
		double upperBand,
		double lowerBand,
		double atr) {
}

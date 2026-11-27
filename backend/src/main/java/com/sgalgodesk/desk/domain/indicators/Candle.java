package com.sgalgodesk.desk.domain.indicators;

import java.time.Instant;

/** OHLC bar (pure domain model). */
public record Candle(
		Instant timestamp,
		double open,
		double high,
		double low,
		double close,
		long volume,
		long openInterest) {
}

package com.sgalgodesk.desk.application.indicators;

import java.time.Instant;

/**
 * One EMA sample aligned with a candle timestamp.
 */
public record EmaPoint(Instant timestamp, double value) {
}

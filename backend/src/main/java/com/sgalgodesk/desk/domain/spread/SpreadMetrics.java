package com.sgalgodesk.desk.domain.spread;

/**
 * Mark / P&amp;L figures for a spread at a given set of leg prices.
 * {@code spreadMark1Lot} and {@code combinedPremium1Lot} are per-unit premium points (not × quantity).
 * {@code runningPnl1Lot} is rupees for one lot; {@code runningPnlFull} is full-position rupees.
 */
public record SpreadMetrics(
		double spreadMark1Lot,
		double runningPnl1Lot,
		double runningPnlFull,
		double combinedPremium1Lot,
		int lots,
		String kind) {
}

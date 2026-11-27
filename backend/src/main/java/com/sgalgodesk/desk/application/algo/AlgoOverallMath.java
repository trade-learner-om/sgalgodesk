package com.sgalgodesk.desk.application.algo;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

/**
 * Overall SL / target helpers in premium points (qty-independent).
 */
public final class AlgoOverallMath {

	private AlgoOverallMath() {
	}

	public static boolean isPointsOrPremium(String type) {
		if (type == null || type.isBlank()) {
			return true;
		}
		String u = type.trim().toUpperCase(Locale.ROOT);
		return u.startsWith("POINT") || u.startsWith("PREMIUM");
	}

	/**
	 * Premium-point PnL (no qty). Short: entry − mark; long: mark − entry.
	 */
	public static double premiumPointPnl(String side, double entry, double mark) {
		if (!(entry > 0) || !(mark > 0)) {
			return 0.0;
		}
		boolean buy = side != null && side.trim().toUpperCase(Locale.ROOT).startsWith("B");
		double raw = buy ? (mark - entry) : (entry - mark);
		return Math.round(raw * 100.0) / 100.0;
	}

	/** Sum of premium-point PnL for closed trades (uses exitPrice). */
	public static double closedPremiumPointPnl(Collection<AlgoStrategyTrade> closed) {
		if (closed == null || closed.isEmpty()) {
			return 0.0;
		}
		double sum = 0.0;
		for (AlgoStrategyTrade t : closed) {
			if (t.exitPrice() == null || !(t.exitPrice() > 0)) {
				continue;
			}
			sum += premiumPointPnl(t.side(), t.entryPrice(), t.exitPrice());
		}
		return Math.round(sum * 100.0) / 100.0;
	}

	/** Sum of premium-point PnL for open trades at given LTPs. */
	public static double openPremiumPointPnl(
			Collection<AlgoStrategyTrade> open,
			Map<String, Double> prices) {
		if (open == null || open.isEmpty()) {
			return 0.0;
		}
		double sum = 0.0;
		for (AlgoStrategyTrade t : open) {
			double ltp = prices != null
					? prices.getOrDefault(t.instrumentKey(), t.entryPrice())
					: t.entryPrice();
			sum += premiumPointPnl(t.side(), t.entryPrice(), ltp);
		}
		return Math.round(sum * 100.0) / 100.0;
	}

	public static double combinedPremiumPointPnl(
			Collection<AlgoStrategyTrade> closed,
			Collection<AlgoStrategyTrade> open,
			Map<String, Double> openPrices) {
		return Math.round((closedPremiumPointPnl(closed) + openPremiumPointPnl(open, openPrices)) * 100.0)
				/ 100.0;
	}

	/**
	 * Adverse premium points already realised on closed legs (positive number).
	 * e.g. short closed at +70 pts loss → 70.
	 */
	public static double consumedOverallPoints(Collection<AlgoStrategyTrade> closed) {
		double pnl = closedPremiumPointPnl(closed);
		return Math.round(Math.max(0.0, -pnl) * 100.0) / 100.0;
	}

	/** Remaining overall SL budget in premium points after closed-leg consumption. */
	public static double remainingOverallBudget(double overallSlValue, Collection<AlgoStrategyTrade> closed) {
		double remaining = Math.abs(overallSlValue) - consumedOverallPoints(closed);
		return Math.round(remaining * 100.0) / 100.0;
	}

	/**
	 * New SL trigger from remaining overall budget. Never loosens vs currentTrigger.
	 * SELL: entry + remaining; BUY: entry − remaining.
	 */
	public static Double tightenedSlTrigger(
			String side,
			double entry,
			double remainingPoints,
			Double currentTrigger) {
		if (!(entry > 0) || !(remainingPoints > 0)) {
			return null;
		}
		Double fromBudget = AlgoSlMath.computeSlTrigger(side, entry, "Points", remainingPoints);
		if (fromBudget == null) {
			return currentTrigger;
		}
		if (currentTrigger == null || !(currentTrigger > 0)) {
			return fromBudget;
		}
		boolean sell = side != null && side.trim().toUpperCase(Locale.ROOT).startsWith("S");
		// Never loosen: for short, lower trigger is tighter; for long, higher is tighter
		if (sell) {
			return Math.min(currentTrigger, fromBudget);
		}
		return Math.max(currentTrigger, fromBudget);
	}
}

package com.sgalgodesk.desk.application.algo;

import java.util.Locale;

/**
 * Per-leg stop-loss trigger helpers. Pure functions for unit testing.
 */
public final class AlgoSlMath {

	private AlgoSlMath() {
	}

	/**
	 * Computes SL-M trigger from fill price.
	 * BUY: fill - offset; SELL: fill + offset.
	 * Points/Premium → absolute premium points; Percentage → fill * value/100.
	 *
	 * @return trigger price, or null if invalid
	 */
	public static Double computeSlTrigger(String side, double fill, String unit, Double value) {
		if (value == null || !(fill > 0)) {
			return null;
		}
		double offset;
		String u = unit == null ? "POINTS" : unit.trim().toUpperCase(Locale.ROOT);
		if (u.startsWith("PERCENT")) {
			offset = fill * Math.abs(value) / 100.0;
		}
		else {
			offset = Math.abs(value);
		}
		if (!(offset > 0)) {
			return null;
		}
		boolean sell = side != null && side.trim().toUpperCase(Locale.ROOT).startsWith("S");
		double trigger = sell ? fill + offset : fill - offset;
		if (!(trigger > 0)) {
			return null;
		}
		return Math.round(trigger * 100.0) / 100.0;
	}

	/** True when LTP has breached the software SL for this side. */
	public static boolean softwareSlBreached(String side, double ltp, double trigger) {
		if (!(ltp > 0) || !(trigger > 0)) {
			return false;
		}
		boolean sell = side != null && side.trim().toUpperCase(Locale.ROOT).startsWith("S");
		return sell ? ltp >= trigger : ltp <= trigger;
	}
}

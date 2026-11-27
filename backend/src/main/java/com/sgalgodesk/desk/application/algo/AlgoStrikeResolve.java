package com.sgalgodesk.desk.application.algo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure strike resolution for ITM/ATM/OTM ladder and CMP fallback.
 */
public final class AlgoStrikeResolve {

	public static final double NIFTY_STRIKE_STEP = 50.0;
	/** Last-resort only when catalog/chain lotSize is missing. Prefer broker instrument lotSize. */
	public static final int NIFTY_DEFAULT_LOT = 65;

	/**
	 * Prefer catalog lot, then instrument/chain lot, then {@code configFallback}, then
	 * {@link #NIFTY_DEFAULT_LOT}.
	 */
	public static int resolveLotSize(
			Integer catalogLot,
			Integer instrumentLot,
			Integer chainLot,
			Integer configFallback) {
		if (catalogLot != null && catalogLot > 0) {
			return catalogLot;
		}
		if (instrumentLot != null && instrumentLot > 0) {
			return instrumentLot;
		}
		if (chainLot != null && chainLot > 0) {
			return chainLot;
		}
		if (configFallback != null && configFallback > 0) {
			return configFallback;
		}
		return NIFTY_DEFAULT_LOT;
	}

	/** @deprecated use {@link #resolveLotSize(Integer, Integer, Integer, Integer)} */
	public static int resolveLotSize(Integer catalogLot, Integer instrumentLot, Integer chainLot) {
		return resolveLotSize(catalogLot, instrumentLot, chainLot, null);
	}

	public static boolean usedLotSizeFallback(Integer catalogLot, Integer instrumentLot, Integer chainLot) {
		return (catalogLot == null || catalogLot <= 0)
				&& (instrumentLot == null || instrumentLot <= 0)
				&& (chainLot == null || chainLot <= 0);
	}
	public static final String NIFTY_CMP_KEY = "NSE:NIFTY50";

	private static final Pattern ITM = Pattern.compile("^ITM(\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern OTM = Pattern.compile("^OTM(\\d+)$", Pattern.CASE_INSENSITIVE);

	private AlgoStrikeResolve() {
	}

	public enum Moneyness {
		ATM, ITM, OTM, LEGACY_OFFSET, PREMIUM, ABSOLUTE, UNKNOWN
	}

	public record ParsedCriteria(Moneyness moneyness, int steps, Double absoluteOrPremium) {
	}

	public static ParsedCriteria parseCriteria(String criteria, Double strikeParam1) {
		if (criteria == null || criteria.isBlank()) {
			return new ParsedCriteria(Moneyness.ATM, 0, null);
		}
		String c = criteria.trim().toUpperCase(Locale.ROOT);
		if ("ATM".equals(c)) {
			return new ParsedCriteria(Moneyness.ATM, 0, null);
		}
		Matcher itm = ITM.matcher(c);
		if (itm.matches()) {
			return new ParsedCriteria(Moneyness.ITM, Integer.parseInt(itm.group(1)), null);
		}
		Matcher otm = OTM.matcher(c);
		if (otm.matches()) {
			return new ParsedCriteria(Moneyness.OTM, Integer.parseInt(otm.group(1)), null);
		}
		if (c.contains("PREMIUM")) {
			return new ParsedCriteria(Moneyness.PREMIUM, 0, strikeParam1);
		}
		if (c.contains("ABSOLUTE") || "STRIKE".equals(c)) {
			return new ParsedCriteria(Moneyness.ABSOLUTE, 0, strikeParam1);
		}
		if (c.contains("OFFSET") || c.contains("ITM") || c.contains("OTM") || c.contains("+")) {
			int steps = strikeParam1 != null ? (int) Math.round(strikeParam1) : 0;
			return new ParsedCriteria(Moneyness.LEGACY_OFFSET, steps, null);
		}
		return new ParsedCriteria(Moneyness.UNKNOWN, 0, null);
	}

	/** Signed step count from ATM: CE ITM negative, PE ITM positive, etc. */
	public static int signedStepsFromAtm(boolean call, Moneyness moneyness, int n) {
		if (moneyness == Moneyness.ATM || n == 0) {
			return 0;
		}
		if (moneyness == Moneyness.LEGACY_OFFSET) {
			return n;
		}
		if (moneyness == Moneyness.ITM) {
			return call ? -n : n;
		}
		if (moneyness == Moneyness.OTM) {
			return call ? n : -n;
		}
		return 0;
	}

	public static double roundToStep(double value, double step) {
		if (!(step > 0)) {
			return value;
		}
		return Math.round(value / step) * step;
	}

	public static double atmFromSpot(double spot, double step) {
		return roundToStep(spot, step);
	}

	public static double targetStrike(double atm, double step, int signedSteps) {
		return atm + signedSteps * step;
	}

	/**
	 * Median adjacent gap; falls back to {@link #NIFTY_STRIKE_STEP} when &lt; 2 strikes.
	 */
	public static double medianStep(List<Double> sortedStrikes) {
		if (sortedStrikes == null || sortedStrikes.size() < 2) {
			return NIFTY_STRIKE_STEP;
		}
		List<Double> gaps = new ArrayList<>();
		for (int i = 1; i < sortedStrikes.size(); i++) {
			double g = sortedStrikes.get(i) - sortedStrikes.get(i - 1);
			if (g > 0) {
				gaps.add(g);
			}
		}
		if (gaps.isEmpty()) {
			return NIFTY_STRIKE_STEP;
		}
		gaps.sort(Double::compareTo);
		return gaps.get(gaps.size() / 2);
	}

	public static Double nearestStrike(List<Double> sortedStrikes, double target) {
		if (sortedStrikes == null || sortedStrikes.isEmpty()) {
			return null;
		}
		return sortedStrikes.stream()
				.min(Comparator.comparingDouble(s -> Math.abs(s - target)))
				.orElse(null);
	}

	/**
	 * Full ladder/absolute/legacy resolve to a numeric strike.
	 * For PREMIUM, returns null (caller must use chain premiums).
	 */
	public static Double resolveNumericStrike(
			String criteria,
			Double strikeParam1,
			boolean call,
			double spot,
			double step,
			List<Double> listedStrikes) {
		ParsedCriteria parsed = parseCriteria(criteria, strikeParam1);
		if (parsed.moneyness() == Moneyness.PREMIUM) {
			return null;
		}
		if (parsed.moneyness() == Moneyness.ABSOLUTE) {
			if (parsed.absoluteOrPremium() == null) {
				return null;
			}
			double abs = parsed.absoluteOrPremium();
			if (listedStrikes != null && !listedStrikes.isEmpty()) {
				Double nearest = nearestStrike(listedStrikes, abs);
				return nearest != null ? nearest : abs;
			}
			return abs;
		}
		double atm;
		if (listedStrikes != null && !listedStrikes.isEmpty()) {
			Double nearestAtm = nearestStrike(listedStrikes, spot);
			atm = nearestAtm != null ? nearestAtm : atmFromSpot(spot, step);
		}
		else {
			atm = atmFromSpot(spot, step);
		}
		int signed = signedStepsFromAtm(call, parsed.moneyness(), parsed.steps());
		double target = targetStrike(atm, step, signed);
		if (listedStrikes != null && !listedStrikes.isEmpty()) {
			Double nearest = nearestStrike(listedStrikes, target);
			return nearest != null ? nearest : target;
		}
		return target;
	}

	public static boolean isCall(String optionType) {
		if (optionType == null) {
			return true;
		}
		String t = optionType.trim().toUpperCase(Locale.ROOT);
		return t.startsWith("C") || t.contains("CALL");
	}
}

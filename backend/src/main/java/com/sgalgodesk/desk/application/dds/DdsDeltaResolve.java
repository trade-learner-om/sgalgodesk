package com.sgalgodesk.desk.application.dds;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.sgalgodesk.desk.application.dds.DdsBlackScholes.OptionKind;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionSide;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;

/**
 * Pick CE/PE strike nearest to a target absolute delta, preferring lesser-or-equal |delta|.
 */
public final class DdsDeltaResolve {

	public record Candidate(
			String optionType,
			double strike,
			String instrumentKey,
			String tradingSymbol,
			Double ltp,
			double absDelta,
			Double signedDelta) {
	}

	private DdsDeltaResolve() {
	}

	/**
	 * @param targetAbsDelta e.g. 25 meaning 0.25 absolute delta
	 * @param prices map of instrumentKey → LTP (may be incomplete)
	 */
	public static Candidate pick(
			OptionChainResponse chain,
			String optionType,
			double spot,
			LocalDate today,
			LocalDate expiry,
			double targetAbsDelta,
			java.util.Map<String, Double> prices) {
		if (chain == null || chain.strikes() == null || chain.strikes().isEmpty()) {
			throw new IllegalStateException("Option chain required for delta resolve");
		}
		if (spot <= 0) {
			throw new IllegalStateException("Spot required for delta resolve");
		}
		double target = targetAbsDelta > 1.0 ? targetAbsDelta / 100.0 : targetAbsDelta;
		if (target <= 0) {
			throw new IllegalArgumentException("targetAbsDelta must be positive");
		}
		OptionKind kind = "PE".equalsIgnoreCase(optionType) ? OptionKind.PE : OptionKind.CE;
		double years = Math.max(ChronoUnit.DAYS.between(today, expiry) / 365.0, 1.0 / 365.0);

		List<Candidate> candidates = new ArrayList<>();
		for (OptionStrikeRow row : chain.strikes()) {
			OptionSide side = kind == OptionKind.CE ? row.call() : row.put();
			if (side == null || side.instrumentKey() == null) {
				continue;
			}
			Double ltp = prices != null ? prices.get(side.instrumentKey()) : null;
			if (ltp == null || ltp <= 0) {
				ltp = side.ltp();
			}
			Double signed;
			if (side.delta() != null && Double.isFinite(side.delta())) {
				signed = side.delta();
			}
			else if (ltp != null && ltp > 0) {
				Double iv = DdsBlackScholes.impliedVolatility(ltp, spot, row.strike(), years, kind, 0);
				if (iv == null) {
					iv = 0.15;
				}
				signed = DdsBlackScholes.delta(spot, row.strike(), years, iv, kind, 0);
			}
			else {
				continue;
			}
			double abs = Math.abs(signed);
			String sym = side.tradingSymbol() != null
					? side.tradingSymbol()
					: optionType.toUpperCase(Locale.ROOT) + "-" + (int) row.strike();
			candidates.add(new Candidate(
					optionType.toUpperCase(Locale.ROOT),
					row.strike(),
					side.instrumentKey(),
					sym,
					ltp,
					abs,
					signed));
		}
		if (candidates.isEmpty()) {
			throw new IllegalStateException("No delta candidates for " + optionType);
		}

		// Prefer absDelta <= target; among those pick closest to target (largest abs = nearest lesser).
		List<Candidate> lesserOrEq = candidates.stream()
				.filter(c -> c.absDelta() <= target + 1e-9)
				.sorted(Comparator.comparingDouble((Candidate c) -> target - c.absDelta()))
				.toList();
		if (!lesserOrEq.isEmpty()) {
			return lesserOrEq.getFirst();
		}
		// Fallback: closest overall (slightly higher delta)
		return candidates.stream()
				.min(Comparator.comparingDouble(c -> Math.abs(c.absDelta() - target)))
				.orElseThrow();
	}

	public static boolean isItm(String optionType, double strike, double spot) {
		if ("CE".equalsIgnoreCase(optionType)) {
			return spot > strike;
		}
		if ("PE".equalsIgnoreCase(optionType)) {
			return spot < strike;
		}
		return false;
	}
}

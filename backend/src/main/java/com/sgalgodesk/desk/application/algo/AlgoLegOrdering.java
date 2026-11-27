package com.sgalgodesk.desk.application.algo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.LegConfig;
import com.sgalgodesk.desk.application.algo.AlgoStrategyService.ResolvedLeg;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

/**
 * Entry/exit sequencing for multi-leg options. Pure helpers for unit testing.
 */
public final class AlgoLegOrdering {

	private AlgoLegOrdering() {
	}

	/**
	 * Same-type spread: all CE or all PE, and both BUY and SELL present.
	 */
	public static boolean isSameTypeSpread(List<LegConfig> legs) {
		if (legs == null || legs.size() < 2) {
			return false;
		}
		Boolean call = null;
		boolean hasBuy = false;
		boolean hasSell = false;
		for (LegConfig leg : legs) {
			boolean isCall = isCall(leg.optionType());
			if (call == null) {
				call = isCall;
			}
			else if (call != isCall) {
				return false;
			}
			String side = normalizeSide(leg.position());
			if ("SELL".equals(side)) {
				hasSell = true;
			}
			else {
				hasBuy = true;
			}
		}
		return hasBuy && hasSell;
	}

	/** Entry order: BUY then SELL when same-type spread; else config order (legIndex). */
	public static List<ResolvedLeg> orderForEntry(List<ResolvedLeg> legs, boolean sameTypeSpread) {
		List<ResolvedLeg> copy = new ArrayList<>(legs);
		if (sameTypeSpread) {
			copy.sort(Comparator
					.comparingInt((ResolvedLeg l) -> "SELL".equalsIgnoreCase(l.side()) ? 1 : 0)
					.thenComparingInt(ResolvedLeg::legIndex));
		}
		else {
			copy.sort(Comparator.comparingInt(ResolvedLeg::legIndex));
		}
		return copy;
	}

	/** Exit order: SELL-side positions first, then BUY-side (stable by legIndex). */
	public static <T> List<T> orderForExit(List<T> items, Function<T, String> sideFn, Function<T, Integer> legIndexFn) {
		List<T> copy = new ArrayList<>(items);
		copy.sort(Comparator
				.comparingInt((T t) -> "BUY".equalsIgnoreCase(sideFn.apply(t)) ? 1 : 0)
				.thenComparingInt(legIndexFn::apply));
		return copy;
	}

	public static List<AlgoStrategyTrade> orderTradesForExit(List<AlgoStrategyTrade> trades) {
		return orderForExit(trades, AlgoStrategyTrade::side, AlgoStrategyTrade::legIndex);
	}

	public static boolean isCall(String optionType) {
		if (optionType == null) {
			return true;
		}
		String t = optionType.trim().toUpperCase(Locale.ROOT);
		return t.startsWith("C") || t.contains("CALL");
	}

	public static String normalizeSide(String position) {
		if (position != null && position.trim().toUpperCase(Locale.ROOT).startsWith("S")) {
			return "SELL";
		}
		return "BUY";
	}
}

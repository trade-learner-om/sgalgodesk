package com.sgalgodesk.desk.domain.spread;

import java.util.List;
import java.util.Map;

/** Pure spread mark / P&amp;L computation. */
public final class SpreadCalculator {

	private SpreadCalculator() {
	}

	/**
	 * Combined premium and spread mark are per-unit premium points (not × lotSize / quantity).
	 * Credit mark is sell − buy; debit mark is buy − sell so both align with positive combined premium.
	 * Running P&amp;L remains quantity-scaled (rupees).
	 */
	public static SpreadMetrics computeMetrics(List<SpreadLeg> legs, Map<String, Double> lastLtp) {
		double credit = 0;
		double debit = 0;
		double rawMark = 0;
		int priced = 0;
		double runningPnl1 = 0;
		double runningPnlFull = 0;
		int maxLots = 1;
		for (SpreadLeg leg : legs) {
			int lotSize = Math.max(1, leg.lotSize());
			int fullQty = Math.max(lotSize, Math.abs(leg.quantity()));
			int lots = Math.max(1, leg.lots() > 0 ? leg.lots() : Math.max(1, fullQty / lotSize));
			maxLots = Math.max(maxLots, lots);

			double entryPx = Math.abs(leg.entryPrice());
			if (leg.side() > 0) {
				debit += entryPx;
			}
			else {
				credit += entryPx;
			}

			Double ltp = lastLtp.get(leg.instrumentKey());
			if (ltp == null || ltp <= 0) {
				continue;
			}
			priced++;
			// rawMark = Σ(side × LTP) = buy − sell
			rawMark += leg.side() * ltp;
			if (leg.side() > 0) {
				runningPnl1 += lotSize * (ltp - leg.entryPrice());
				runningPnlFull += fullQty * (ltp - leg.entryPrice());
			}
			else {
				runningPnl1 += lotSize * (leg.entryPrice() - ltp);
				runningPnlFull += fullQty * (leg.entryPrice() - ltp);
			}
		}
		if (priced == 0) {
			return null;
		}
		boolean isCredit = credit >= debit;
		double combined = isCredit ? credit - debit : debit - credit;
		String kind = isCredit ? "credit" : "debit";
		// Credit: sell − buy = −rawMark; debit: buy − sell = rawMark
		double mark = isCredit ? -rawMark : rawMark;
		return new SpreadMetrics(mark, runningPnl1, runningPnlFull, combined, maxLots, kind);
	}
}

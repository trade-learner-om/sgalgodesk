package com.sgalgodesk.desk.domain.spread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SpreadCalculatorTest {

	@Test
	void creditVertical_atEntry_markEqualsPremium_perUnit() {
		// Sell 120 / buy 80 → combined premium 40; mark at entry prices = 40
		List<SpreadLeg> legs = List.of(
				leg("SELL_LEG", -1, 75, 75, 1, 120.0),
				leg("BUY_LEG", 1, 75, 75, 1, 80.0));
		SpreadMetrics m = SpreadCalculator.computeMetrics(legs, Map.of("SELL_LEG", 120.0, "BUY_LEG", 80.0));
		assertNotNull(m);
		assertEquals("credit", m.kind());
		assertEquals(40.0, m.combinedPremium1Lot(), 1e-9);
		assertEquals(40.0, m.spreadMark1Lot(), 1e-9);
	}

	@Test
	void debitVertical_atEntry_markEqualsPremium_perUnit() {
		// Buy 120 / sell 80 → combined premium 40; mark at entry = 40
		List<SpreadLeg> legs = List.of(
				leg("BUY_LEG", 1, 75, 75, 1, 120.0),
				leg("SELL_LEG", -1, 75, 75, 1, 80.0));
		SpreadMetrics m = SpreadCalculator.computeMetrics(legs, Map.of("BUY_LEG", 120.0, "SELL_LEG", 80.0));
		assertNotNull(m);
		assertEquals("debit", m.kind());
		assertEquals(40.0, m.combinedPremium1Lot(), 1e-9);
		assertEquals(40.0, m.spreadMark1Lot(), 1e-9);
	}

	@Test
	void markAndPremium_independentOfLotSizeAndLots() {
		List<SpreadLeg> oneLot = List.of(
				leg("SELL_LEG", -1, 75, 75, 1, 120.0),
				leg("BUY_LEG", 1, 75, 75, 1, 80.0));
		List<SpreadLeg> twoLotsBigger = List.of(
				leg("SELL_LEG", -1, 150, 75, 2, 120.0),
				leg("BUY_LEG", 1, 150, 75, 2, 80.0));
		Map<String, Double> prices = Map.of("SELL_LEG", 110.0, "BUY_LEG", 85.0);

		SpreadMetrics a = SpreadCalculator.computeMetrics(oneLot, prices);
		SpreadMetrics b = SpreadCalculator.computeMetrics(twoLotsBigger, prices);
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(a.combinedPremium1Lot(), b.combinedPremium1Lot(), 1e-9);
		assertEquals(a.spreadMark1Lot(), b.spreadMark1Lot(), 1e-9);
		assertEquals(40.0, a.combinedPremium1Lot(), 1e-9);
		// Credit mark = sell − buy = 110 − 85 = 25
		assertEquals(25.0, a.spreadMark1Lot(), 1e-9);
	}

	@Test
	void fullPnl_scalesWithQuantity() {
		List<SpreadLeg> oneLot = List.of(
				leg("SELL_LEG", -1, 75, 75, 1, 120.0),
				leg("BUY_LEG", 1, 75, 75, 1, 80.0));
		List<SpreadLeg> twoLots = List.of(
				leg("SELL_LEG", -1, 150, 75, 2, 120.0),
				leg("BUY_LEG", 1, 150, 75, 2, 80.0));
		Map<String, Double> prices = Map.of("SELL_LEG", 110.0, "BUY_LEG", 85.0);

		SpreadMetrics a = SpreadCalculator.computeMetrics(oneLot, prices);
		SpreadMetrics b = SpreadCalculator.computeMetrics(twoLots, prices);
		assertNotNull(a);
		assertNotNull(b);
		// Sell: (120−110)×qty; buy: (85−80)×qty → 1-lot: 750 + 375 = 1125
		assertEquals(1125.0, a.runningPnlFull(), 1e-9);
		assertEquals(2250.0, b.runningPnlFull(), 1e-9);
		assertEquals(a.runningPnl1Lot(), b.runningPnl1Lot(), 1e-9);
	}

	private static SpreadLeg leg(
			String key, int side, int quantity, int lotSize, int lots, double entry) {
		return new SpreadLeg(key, side, quantity, lotSize, lots, entry);
	}
}

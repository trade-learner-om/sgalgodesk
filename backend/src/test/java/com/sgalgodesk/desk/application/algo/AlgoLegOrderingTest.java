package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.LegConfig;
import com.sgalgodesk.desk.application.algo.AlgoStrategyService.ResolvedLeg;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

class AlgoLegOrderingTest {

	@Test
	void detectsSameTypeCeSpread() {
		assertTrue(AlgoLegOrdering.isSameTypeSpread(List.of(
				leg("Buy", "CE"),
				leg("Sell", "CE"))));
	}

	@Test
	void detectsSameTypePeSpread() {
		assertTrue(AlgoLegOrdering.isSameTypeSpread(List.of(
				leg("Sell", "PE"),
				leg("Buy", "PE"))));
	}

	@Test
	void rejectsMixedCePe() {
		assertFalse(AlgoLegOrdering.isSameTypeSpread(List.of(
				leg("Buy", "CE"),
				leg("Sell", "PE"))));
	}

	@Test
	void rejectsSameSideOnly() {
		assertFalse(AlgoLegOrdering.isSameTypeSpread(List.of(
				leg("Buy", "CE"),
				leg("Buy", "CE"))));
	}

	@Test
	void entryOrdersBuyBeforeSellForSameTypeSpread() {
		List<ResolvedLeg> legs = List.of(
				resolved(0, "SELL"),
				resolved(1, "BUY"));
		List<ResolvedLeg> ordered = AlgoLegOrdering.orderForEntry(legs, true);
		assertEquals("BUY", ordered.get(0).side());
		assertEquals("SELL", ordered.get(1).side());
	}

	@Test
	void entryKeepsLegIndexOrderWhenNotSpread() {
		List<ResolvedLeg> legs = List.of(
				resolved(0, "SELL"),
				resolved(1, "BUY"));
		List<ResolvedLeg> ordered = AlgoLegOrdering.orderForEntry(legs, false);
		assertEquals(0, ordered.get(0).legIndex());
		assertEquals(1, ordered.get(1).legIndex());
	}

	@Test
	void exitOrdersSellBeforeBuy() {
		List<AlgoStrategyTrade> trades = List.of(
				trade(0, "BUY"),
				trade(1, "SELL"));
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(trades);
		assertEquals("SELL", ordered.get(0).side());
		assertEquals("BUY", ordered.get(1).side());
	}

	private static LegConfig leg(String position, String optionType) {
		return new LegConfig(
				"Options",
				1,
				position,
				optionType,
				"Weekly",
				"ATM",
				"Strike",
				null,
				null,
				false,
				"Points",
				null,
				false,
				"Points",
				null,
				false,
				"Points",
				null,
				null,
				false,
				"Immediate",
				1,
				false,
				"Immediate",
				1,
				false,
				null,
				"High",
				"Underlying",
				false,
				"Points",
				null);
	}

	private static ResolvedLeg resolved(int idx, String side) {
		return new ResolvedLeg(idx, "NFO:X", "X", side, 25, 100.0, null, null, null, 25, 24000.0, null, "CE");
	}

	private static AlgoStrategyTrade trade(int idx, String side) {
		return new AlgoStrategyTrade(
				java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(),
				idx,
				"NFO:X",
				"X",
				side,
				25,
				"PAPER",
				null,
				null,
				null,
				100.0,
				null,
				0,
				"ENTRY",
				"OPEN",
				java.time.Instant.now(),
				null,
				25,
				24000.0,
				null,
				"CE");
	}
}

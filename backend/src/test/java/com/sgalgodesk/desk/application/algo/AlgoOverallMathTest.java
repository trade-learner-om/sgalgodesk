package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

class AlgoOverallMathTest {

	@Test
	void shortPremiumPointPnl() {
		assertEquals(-70.0, AlgoOverallMath.premiumPointPnl("SELL", 120.0, 190.0));
		assertEquals(10.0, AlgoOverallMath.premiumPointPnl("SELL", 120.0, 110.0));
	}

	@Test
	void remainingBudgetAfterLegSl() {
		AlgoStrategyTrade closed = closedSell(120.0, 190.0); // −70 pts
		assertEquals(70.0, AlgoOverallMath.consumedOverallPoints(List.of(closed)));
		assertEquals(30.0, AlgoOverallMath.remainingOverallBudget(100.0, List.of(closed)));
	}

	@Test
	void tightenSellTriggerToEntryPlusRemaining() {
		Double trigger = AlgoOverallMath.tightenedSlTrigger("SELL", 130.0, 30.0, 200.0);
		assertEquals(160.0, trigger); // entry+30, tighter than entry+70
	}

	@Test
	void tightenNeverLoosens() {
		// remaining 80 would be entry+80=210, but current is 200 → keep 200
		Double trigger = AlgoOverallMath.tightenedSlTrigger("SELL", 130.0, 80.0, 200.0);
		assertEquals(200.0, trigger);
	}

	@Test
	void combinedOpenAndClosed() {
		AlgoStrategyTrade closed = closedSell(120.0, 190.0); // −70
		AlgoStrategyTrade open = openSell(130.0);
		double combined = AlgoOverallMath.combinedPremiumPointPnl(
				List.of(closed), List.of(open), Map.of(open.instrumentKey(), 160.0));
		// closed −70 + open (130−160)=−30 → −100
		assertEquals(-100.0, combined);
	}

	@Test
	void exhaustedBudgetIsNonPositive() {
		AlgoStrategyTrade closed = closedSell(120.0, 230.0); // −110
		assertTrue(AlgoOverallMath.remainingOverallBudget(100.0, List.of(closed)) <= 0);
		assertNull(AlgoOverallMath.tightenedSlTrigger("SELL", 130.0, 0.0, 200.0));
	}

	private static AlgoStrategyTrade closedSell(double entry, double exit) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				0, "NFO:PE", "PE", "SELL", 65, "PAPER", null, null, entry + 70,
				entry, exit, (entry - exit) * 65, "LEG_SL", "CLOSED",
				Instant.now(), Instant.now(), 65, 24300.0, null, "PE");
	}

	private static AlgoStrategyTrade openSell(double entry) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				1, "NFO:CE", "CE", "SELL", 65, "PAPER", null, null, entry + 70,
				entry, null, 0, "ENTRY", "OPEN",
				Instant.now(), null, 65, 24200.0, null, "CE");
	}
}

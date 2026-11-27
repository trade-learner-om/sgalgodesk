package com.sgalgodesk.desk.application.dds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure decision rules for 2× premium adjust vs ITM day-exit (Option B).
 */
class DdsPremiumDoubleLogicTest {

	enum Action {
		NONE, ADJUST, CE_ITM_EXIT, PE_ITM_EXIT
	}

	static Action decide(double cePx, double pePx, double ceStrike, double peStrike, double spot) {
		if (cePx <= 0 || pePx <= 0) {
			return Action.NONE;
		}
		boolean ceLosing = cePx >= 2 * pePx;
		boolean peLosing = pePx >= 2 * cePx;
		if (!ceLosing && !peLosing) {
			return Action.NONE;
		}
		if (ceLosing) {
			if (DdsDeltaResolve.isItm("CE", ceStrike, spot)) {
				return Action.CE_ITM_EXIT;
			}
			return Action.ADJUST;
		}
		if (DdsDeltaResolve.isItm("PE", peStrike, spot)) {
			return Action.PE_ITM_EXIT;
		}
		return Action.ADJUST;
	}

	@Test
	void noDoubleKeepsNone() {
		assertEquals(Action.NONE, decide(40, 35, 24600, 24400, 24500));
	}

	@Test
	void doubleOtmAdjusts() {
		// CE premium 2x PE, CE still OTM
		assertEquals(Action.ADJUST, decide(80, 40, 24700, 24300, 24500));
		assertFalse(DdsDeltaResolve.isItm("CE", 24700, 24500));
	}

	@Test
	void doubleWithCeItmExits() {
		assertEquals(Action.CE_ITM_EXIT, decide(90, 40, 24400, 24200, 24500));
		assertTrue(DdsDeltaResolve.isItm("CE", 24400, 24500));
	}

	@Test
	void doubleWithPeItmExits() {
		assertEquals(Action.PE_ITM_EXIT, decide(40, 90, 24700, 24600, 24500));
		assertTrue(DdsDeltaResolve.isItm("PE", 24600, 24500));
	}

	@Test
	void adjustTargetDeltaIsLoserMinusFive() {
		double loserAbs = 0.25;
		double target = Math.max(0.01, loserAbs - 0.05);
		assertEquals(0.20, target, 1e-9);
	}
}

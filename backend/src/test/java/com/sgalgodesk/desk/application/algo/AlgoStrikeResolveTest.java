package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.algo.AlgoStrikeResolve.Moneyness;

class AlgoStrikeResolveTest {

	@Test
	void ceAndPeItm1AreDifferentStrikes() {
		double spot = 24237;
		double step = 50;
		double atm = AlgoStrikeResolve.atmFromSpot(spot, step);
		assertEquals(24250.0, atm);

		Double ce = AlgoStrikeResolve.resolveNumericStrike("ITM1", null, true, spot, step, null);
		Double pe = AlgoStrikeResolve.resolveNumericStrike("ITM1", null, false, spot, step, null);
		assertEquals(24200.0, ce);
		assertEquals(24300.0, pe);
		assertNotEquals(ce, pe);
	}

	@Test
	void ceAndPeOtm2Directions() {
		double spot = 24250;
		double step = 50;
		assertEquals(24350.0, AlgoStrikeResolve.resolveNumericStrike("OTM2", null, true, spot, step, null));
		assertEquals(24150.0, AlgoStrikeResolve.resolveNumericStrike("OTM2", null, false, spot, step, null));
	}

	@Test
	void atmUsesRoundedSpot() {
		assertEquals(24250.0, AlgoStrikeResolve.resolveNumericStrike("ATM", null, true, 24237, 50, null));
		assertEquals(24250.0, AlgoStrikeResolve.resolveNumericStrike("ATM", null, false, 24237, 50, null));
	}

	@Test
	void bothCeItm1SameStrike() {
		double s = AlgoStrikeResolve.resolveNumericStrike("ITM1", null, true, 24250, 50, null);
		assertEquals(s, AlgoStrikeResolve.resolveNumericStrike("ITM1", null, true, 24250, 50, null));
	}

	@Test
	void snapsToListedStrikesWhenPresent() {
		List<Double> listed = List.of(24100.0, 24150.0, 24200.0, 24250.0, 24300.0);
		Double ce = AlgoStrikeResolve.resolveNumericStrike("ITM1", null, true, 24237, 50, listed);
		assertEquals(24200.0, ce);
	}

	@Test
	void parseLadderBounds() {
		assertEquals(Moneyness.ITM, AlgoStrikeResolve.parseCriteria("ITM10", null).moneyness());
		assertEquals(10, AlgoStrikeResolve.parseCriteria("ITM10", null).steps());
		assertEquals(Moneyness.OTM, AlgoStrikeResolve.parseCriteria("OTM10", null).moneyness());
		assertEquals(10, AlgoStrikeResolve.parseCriteria("OTM10", null).steps());
		assertEquals(Moneyness.ATM, AlgoStrikeResolve.parseCriteria("ATM", null).moneyness());
	}

	@Test
	void premiumReturnsNullNumeric() {
		assertNull(AlgoStrikeResolve.resolveNumericStrike("Closest Premium", 50.0, true, 24250, 50, null));
	}

	@Test
	void cmpFallbackMathExample() {
		double cmp = 24237;
		double atm = AlgoStrikeResolve.atmFromSpot(cmp, AlgoStrikeResolve.NIFTY_STRIKE_STEP);
		assertEquals(24250.0, atm);
		int ceItm1 = AlgoStrikeResolve.signedStepsFromAtm(true, Moneyness.ITM, 1);
		int peItm1 = AlgoStrikeResolve.signedStepsFromAtm(false, Moneyness.ITM, 1);
		assertEquals(24200.0, AlgoStrikeResolve.targetStrike(atm, 50, ceItm1));
		assertEquals(24300.0, AlgoStrikeResolve.targetStrike(atm, 50, peItm1));
	}

	@Test
	void lotSizePrefersCatalogOverChainAndDefault() {
		assertEquals(65, AlgoStrikeResolve.resolveLotSize(65, 75, 50, 40));
		assertEquals(75, AlgoStrikeResolve.resolveLotSize(null, 75, 50, 40));
		assertEquals(50, AlgoStrikeResolve.resolveLotSize(null, null, 50, 40));
		assertEquals(40, AlgoStrikeResolve.resolveLotSize(null, null, null, 40));
		assertEquals(AlgoStrikeResolve.NIFTY_DEFAULT_LOT,
				AlgoStrikeResolve.resolveLotSize(null, null, null, null));
		assertTrue(AlgoStrikeResolve.usedLotSizeFallback(null, null, null));
		assertFalse(AlgoStrikeResolve.usedLotSizeFallback(65, null, null));
	}
}

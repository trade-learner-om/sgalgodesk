package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlgoSlMathTest {

	@Test
	void buyPointsSubtractsOffset() {
		assertEquals(90.0, AlgoSlMath.computeSlTrigger("BUY", 100.0, "Points", 10.0));
	}

	@Test
	void sellPointsAddsOffset() {
		assertEquals(110.0, AlgoSlMath.computeSlTrigger("SELL", 100.0, "Points", 10.0));
	}

	@Test
	void percentageUsesFillFraction() {
		assertEquals(90.0, AlgoSlMath.computeSlTrigger("BUY", 100.0, "Percentage", 10.0));
		assertEquals(110.0, AlgoSlMath.computeSlTrigger("SELL", 100.0, "Percentage", 10.0));
	}

	@Test
	void premiumUnitTreatedAsAbsolutePoints() {
		assertEquals(80.0, AlgoSlMath.computeSlTrigger("BUY", 100.0, "Premium", 20.0));
	}

	@Test
	void invalidInputsReturnNull() {
		assertNull(AlgoSlMath.computeSlTrigger("BUY", 100.0, "Points", null));
		assertNull(AlgoSlMath.computeSlTrigger("BUY", 0.0, "Points", 10.0));
		assertNull(AlgoSlMath.computeSlTrigger("BUY", 5.0, "Points", 10.0));
	}

	@Test
	void softwareSlBreachDirections() {
		assertTrue(AlgoSlMath.softwareSlBreached("BUY", 90.0, 95.0));
		assertFalse(AlgoSlMath.softwareSlBreached("BUY", 100.0, 95.0));
		assertTrue(AlgoSlMath.softwareSlBreached("SELL", 110.0, 105.0));
		assertFalse(AlgoSlMath.softwareSlBreached("SELL", 100.0, 105.0));
	}
}

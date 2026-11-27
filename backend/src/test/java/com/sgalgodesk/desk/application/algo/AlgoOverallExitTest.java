package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.OverallSettings;

class AlgoOverallExitTest {

	@Test
	void overallSlUsesPremiumPointsNotRupees() {
		OverallSettings overall = overall(true, 100.0, "Points", false, null, "Points", false);
		// −100 premium points fires regardless of qty/rupee scale
		assertEquals(
				"OVERALL_SL",
				AlgoStrategyEngine.evaluateOverallExit(-7500.0, -100.0, 0, null, overall));
		assertNull(AlgoStrategyEngine.evaluateOverallExit(-7500.0, -99.0, 0, null, overall));
	}

	@Test
	void overallSlIndependentOfLotSizeScale() {
		OverallSettings overall = overall(true, 100.0, "Points", false, null, "Points", false);
		// Same premium-point loss with tiny or huge rupee MTM
		assertEquals(
				"OVERALL_SL",
				AlgoStrategyEngine.evaluateOverallExit(-100.0, -100.0, 0, null, overall));
		assertEquals(
				"OVERALL_SL",
				AlgoStrategyEngine.evaluateOverallExit(-65000.0, -100.0, 0, null, overall));
	}

	@Test
	void overallTargetUsesPremiumPoints() {
		OverallSettings overall = overall(false, null, "Points", true, 50.0, "Premium", false);
		assertEquals(
				"OVERALL_TARGET",
				AlgoStrategyEngine.evaluateOverallExit(100.0, 50.0, 50, null, overall));
		assertNull(AlgoStrategyEngine.evaluateOverallExit(100.0, 49.0, 49, null, overall));
	}

	@Test
	void trailingLockStillUsesRupeeUnrealised() {
		OverallSettings overall = overall(false, null, "Points", false, null, "Points", true);
		assertEquals(
				"TRAILING_LOCK",
				AlgoStrategyEngine.evaluateOverallExit(200.0, -10.0, 800, 250.0, overall));
		assertNull(AlgoStrategyEngine.evaluateOverallExit(300.0, -10.0, 800, 250.0, overall));
	}

	private static OverallSettings overall(
			boolean sl,
			Double slValue,
			String slType,
			boolean tgt,
			Double tgtValue,
			String tgtType,
			boolean trail) {
		return new OverallSettings(
				sl,
				slType,
				slValue,
				tgt,
				tgtType,
				tgtValue,
				false,
				"Points",
				null,
				false,
				"Immediate",
				1,
				false,
				"Immediate",
				1,
				trail,
				"Lock",
				500.0,
				250.0,
				null,
				null);
	}
}

package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.algo.AlgoStrategyService.ResolvedLeg;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

class AlgoStrategyEngineEntryIdempotencyTest {

	@Test
	void missingEntryLegsSkipsAlreadyOpenLegIndexes() {
		List<ResolvedLeg> ordered = List.of(
				leg(0, "PE"),
				leg(1, "CE"));
		List<AlgoStrategyTrade> open = List.of(openTrade(0, "PE"));

		List<ResolvedLeg> missing = AlgoStrategyEngine.missingEntryLegs(ordered, open);

		assertEquals(1, missing.size());
		assertEquals(1, missing.get(0).legIndex());
		assertEquals("CE", missing.get(0).optionType());
	}

	@Test
	void missingEntryLegsEmptyWhenAllLegsOpenEvenWithDuplicateOpens() {
		List<ResolvedLeg> ordered = List.of(
				leg(0, "PE"),
				leg(1, "CE"));
		// Restart bug shape: many PE fills for the same leg index + one CE
		List<AlgoStrategyTrade> open = List.of(
				openTrade(0, "PE"),
				openTrade(0, "PE"),
				openTrade(0, "PE"),
				openTrade(1, "CE"));

		assertTrue(AlgoStrategyEngine.missingEntryLegs(ordered, open).isEmpty());
		assertTrue(AlgoStrategyEngine.hasAllConfiguredLegsOpen(2, open));
	}

	@Test
	void hasAllConfiguredLegsOpenFalseWhenOnlyPartialRestartFills() {
		List<AlgoStrategyTrade> open = List.of(
				openTrade(0, "PE"),
				openTrade(0, "PE"));

		assertFalse(AlgoStrategyEngine.hasAllConfiguredLegsOpen(2, open));
		assertEquals(List.of(leg(1, "CE")),
				AlgoStrategyEngine.missingEntryLegs(List.of(leg(0, "PE"), leg(1, "CE")), open));
	}

	@Test
	void hasAllConfiguredLegsOpenRequiresEveryIndex() {
		assertFalse(AlgoStrategyEngine.hasAllConfiguredLegsOpen(2, List.of()));
		assertFalse(AlgoStrategyEngine.hasAllConfiguredLegsOpen(0, List.of()));
		assertTrue(AlgoStrategyEngine.hasAllConfiguredLegsOpen(
				2, List.of(openTrade(0, "PE"), openTrade(1, "CE"))));
	}

	@Test
	void overlappingTicksAreSingleFlight() {
		AlgoStrategyEngine engine = newEngineStub();
		assertTrue(engine.tryBeginTick());
		assertFalse(engine.tryBeginTick());
		engine.endTick();
		assertTrue(engine.tryBeginTick());
		engine.endTick();
	}

	/** Minimal stub — tick gate does not use collaborators. */
	private static AlgoStrategyEngine newEngineStub() {
		return new AlgoStrategyEngine(null, null, null, null, null, null, null, null);
	}

	private static ResolvedLeg leg(int index, String optionType) {
		return new ResolvedLeg(
				index,
				"NFO:KEY" + index,
				"SYM" + optionType,
				"SELL",
				65,
				100.0,
				null,
				null,
				null,
				65,
				25800.0,
				"2026-12-01",
				optionType);
	}

	private static AlgoStrategyTrade openTrade(int legIndex, String optionType) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				legIndex,
				"NFO:KEY" + legIndex,
				"SYM" + optionType,
				"SELL",
				65,
				"LIVE",
				"ord-" + legIndex,
				null,
				null,
				237.35,
				null,
				0,
				"ENTRY",
				"OPEN",
				Instant.now(),
				null,
				65,
				25800.0,
				"2026-12-01",
				optionType);
	}
}

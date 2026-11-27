package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AlgoStrategyEngineExpiryTest {

	@Test
	void pickExpiryReturnsEmptyWhenNoDates() {
		assertTrue(AlgoStrategyEngine.pickExpiry(List.of(), false, false).isEmpty());
		assertTrue(AlgoStrategyEngine.pickExpiry(null, false, false).isEmpty());
	}

	@Test
	void pickExpiryDoesNotInventThursday() {
		Optional<String> picked = AlgoStrategyEngine.pickExpiry(
				List.of(), false, false, LocalDate.of(2026, 11, 23));
		assertTrue(picked.isEmpty());
	}

	@Test
	void pickExpiryWeeklyTakesNearestOnOrAfterToday() {
		LocalDate today = LocalDate.of(2026, 11, 23);
		Optional<String> picked = AlgoStrategyEngine.pickExpiry(
				List.of(
						LocalDate.of(2026, 11, 23),
						LocalDate.of(2026, 11, 25),
						LocalDate.of(2026, 12, 1)),
				false,
				false,
				today);
		assertEquals(Optional.of("2026-11-23"), picked);
	}

	@Test
	void pickExpiryNextWeeklyTakesSecondFuture() {
		LocalDate today = LocalDate.of(2026, 11, 23);
		Optional<String> picked = AlgoStrategyEngine.pickExpiry(
				List.of(
						LocalDate.of(2026, 11, 23),
						LocalDate.of(2026, 11, 25),
						LocalDate.of(2026, 12, 1)),
				false,
				true,
				today);
		assertEquals(Optional.of("2026-11-25"), picked);
	}

	@Test
	void assertUnderlyingMatchRejectsFinniftyForNifty() {
		IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> AlgoStrategyEngine.assertUnderlyingMatch(
						"NIFTY",
						"NFO:FINNIFTY26NOV25650PE",
						"FINNIFTY26NOV25650PE"));
		assertTrue(ex.getMessage().contains("underlying mismatch"));
	}

	@Test
	void assertUnderlyingMatchAllowsNiftySymbol() {
		AlgoStrategyEngine.assertUnderlyingMatch(
				"NIFTY",
				"NFO:NIFTY26NOV25650PE",
				"NIFTY26NOV25650PE");
	}

	@Test
	void assertUnderlyingMatchAllowsSyntheticChainSymbol() {
		AlgoStrategyEngine.assertUnderlyingMatch("NIFTY", "NFO:12345", "CE-25550");
	}
}

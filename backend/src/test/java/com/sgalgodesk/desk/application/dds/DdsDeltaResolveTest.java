package com.sgalgodesk.desk.application.dds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.dds.DdsDeltaResolve.Candidate;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionSide;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;

class DdsDeltaResolveTest {

	@Test
	void prefersNearestLesserOrEqualDelta() {
		OptionChainResponse chain = chainWithDeltas(
				row(24500, side("NFO:1", 0.30), side("NFO:2", -0.30)),
				row(24600, side("NFO:3", 0.22), side("NFO:4", -0.22)),
				row(24700, side("NFO:5", 0.18), side("NFO:6", -0.18)));
		Candidate ce = DdsDeltaResolve.pick(
				chain, "CE", 24600, LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 2), 25, Map.of());
		assertEquals(24600, ce.strike(), 0.01);
		assertEquals(0.22, ce.absDelta(), 0.001);
	}

	@Test
	void fallsBackToClosestWhenAllHigher() {
		OptionChainResponse chain = chainWithDeltas(
				row(24500, side("NFO:1", 0.40), null),
				row(24600, side("NFO:3", 0.35), null));
		Candidate ce = DdsDeltaResolve.pick(
				chain, "CE", 24600, LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 2), 25, Map.of());
		assertEquals(24600, ce.strike(), 0.01);
	}

	@Test
	void itmDetection() {
		assertTrue(DdsDeltaResolve.isItm("CE", 24500, 24600));
		assertFalse(DdsDeltaResolve.isItm("CE", 24600, 24500));
		assertTrue(DdsDeltaResolve.isItm("PE", 24600, 24500));
		assertFalse(DdsDeltaResolve.isItm("PE", 24500, 24600));
	}

	private static OptionChainResponse chainWithDeltas(OptionStrikeRow... rows) {
		return new OptionChainResponse(
				"NSE:NIFTY50", "NIFTY", 24600.0, "2026-12-02", null, null, 65, null, null, List.of(rows));
	}

	private static OptionStrikeRow row(double strike, OptionSide call, OptionSide put) {
		return new OptionStrikeRow(strike, call, put, null);
	}

	private static OptionSide side(String key, double delta) {
		return new OptionSide(
				key, "SYM", 100.0, null, null, null, null, null, 0.15, delta, null, null, null, null, null, null, 65);
	}
}

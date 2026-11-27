package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MstockOptionChainExpiryParseTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	void parseExpiriesFromSecondsEpoch() throws Exception {
		long epoch23 = LocalDate.of(2026, 11, 23).atStartOfDay(IST).toEpochSecond();
		long epoch25 = LocalDate.of(2026, 11, 25).atStartOfDay(IST).toEpochSecond();
		JsonNode master = MAPPER.readTree("""
				{
				  "data": {
				    "OPTIDX": ["NIFTY,99999,%d,%d"],
				    "dctExp": {
				      "%d": %d,
				      "%d": %d
				    }
				  }
				}
				""".formatted(epoch23, epoch25, epoch23, epoch23, epoch25, epoch25));

		List<LocalDate> dates = MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 23));
		assertEquals(List.of(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 25)), dates);
	}

	@Test
	void parseExpiriesFromMillisEpoch() throws Exception {
		long epochSec = LocalDate.of(2026, 11, 25).atStartOfDay(IST).toEpochSecond();
		long epochMs = epochSec * 1000L;
		JsonNode master = MAPPER.readTree("""
				{
				  "data": {
				    "OPTIDX": ["NIFTY,99999,%d"],
				    "dctExp": { "%d": %d }
				  }
				}
				""".formatted(epochMs, epochMs, epochMs));

		List<LocalDate> dates = MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 23));
		assertEquals(List.of(LocalDate.of(2026, 11, 25)), dates);
		assertEquals(epochSec, MstockOptionChainService.normalizeEpochSeconds(epochMs));
	}

	@Test
	void resolveExpiryFallsBackToDctWhenRowKeysMismatchButListingDoesNot() throws Exception {
		long epoch23 = LocalDate.of(2026, 11, 23).atStartOfDay(IST).toEpochSecond();
		JsonNode master = MAPPER.readTree("""
				{
				  "data": {
				    "OPTIDX": ["NIFTY,99999,bad-key"],
				    "dctExp": { "%d": %d }
				  }
				}
				""".formatted(epoch23, epoch23));

		String[] row = MstockOptionChainService.findMasterRow(master, "NIFTY");
		assertNotNull(row);
		Long resolved = MstockOptionChainService.resolveExpiryEpoch(
				master, row, LocalDate.of(2026, 11, 23));
		assertEquals(epoch23, resolved);

		// Listing uses OPTIDX row keys only — bad-key yields empty, not all dctExp.
		List<LocalDate> dates = MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 23));
		assertTrue(dates.isEmpty());
	}

	@Test
	void emptyMasterYieldsNoExpiries() throws Exception {
		JsonNode master = MAPPER.readTree("""
				{ "data": { "OPTIDX": [], "dctExp": {} } }
				""");
		assertTrue(MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 23)).isEmpty());
		assertFalse(MstockOptionChainService.findMasterRow(master, "NIFTY") != null);
	}

	@Test
	void niftyRowWithoutExpirySlotsDoesNotListDctExp() throws Exception {
		long epoch23 = LocalDate.of(2026, 11, 23).atStartOfDay(IST).toEpochSecond();
		JsonNode master = MAPPER.readTree("""
				{
				  "data": {
				    "OPTIDX": ["NIFTY,99999"],
				    "dctExp": { "%d": %d }
				  }
				}
				""".formatted(epoch23, epoch23));

		List<LocalDate> dates = MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 23));
		assertTrue(dates.isEmpty());
		Long resolved = MstockOptionChainService.resolveExpiryEpoch(
				master,
				MstockOptionChainService.findMasterRow(master, "NIFTY"),
				LocalDate.of(2026, 11, 23));
		assertEquals(epoch23, resolved);
	}

	@Test
	void phantomSaturdayInDctExpNotListedWhenNotOnNiftyRow() throws Exception {
		// Docs sample dctExp["1"]=1795876200 → 2026-11-28 Saturday; NIFTY row has past-only keys.
		long pastEpoch = LocalDate.of(2019, 1, 1).atStartOfDay(IST).toEpochSecond();
		long saturdayEpoch = 1_795_876_200L;
		JsonNode master = MAPPER.readTree("""
				{
				  "data": {
				    "OPTIDX": ["NIFTY,26000,8"],
				    "dctExp": {
				      "1": %d,
				      "8": %d
				    }
				  }
				}
				""".formatted(saturdayEpoch, pastEpoch));

		List<LocalDate> dates = MstockOptionChainService.parseNiftyExpiryDates(
				master, LocalDate.of(2026, 11, 25));
		assertTrue(dates.isEmpty(), "must not list phantom Saturday from foreign dctExp key");
	}

	@Test
	void mergeExpiryListsPrefersIntersection() {
		List<LocalDate> chain = List.of(
				LocalDate.of(2026, 11, 28),
				LocalDate.of(2026, 12, 1));
		List<LocalDate> script = List.of(
				LocalDate.of(2026, 12, 1),
				LocalDate.of(2026, 12, 8));
		assertEquals(
				List.of(LocalDate.of(2026, 12, 1)),
				MstockOptionChainService.mergeExpiryLists(chain, script));
	}

	@Test
	void mergeExpiryListsPrefersScriptWhenNoOverlap() {
		List<LocalDate> chain = List.of(LocalDate.of(2026, 11, 28));
		List<LocalDate> script = List.of(LocalDate.of(2026, 12, 1));
		assertEquals(
				List.of(LocalDate.of(2026, 12, 1)),
				MstockOptionChainService.mergeExpiryLists(chain, script));
	}

	@Test
	void mergeExpiryListsFallsBackToChainWhenScriptEmpty() {
		List<LocalDate> chain = List.of(LocalDate.of(2026, 11, 28));
		assertEquals(chain, MstockOptionChainService.mergeExpiryLists(chain, List.of()));
	}

	@Test
	void mergeExpiryListsUsesScriptWhenChainEmpty() {
		List<LocalDate> script = List.of(LocalDate.of(2026, 12, 1));
		assertEquals(script, MstockOptionChainService.mergeExpiryLists(List.of(), script));
	}
}

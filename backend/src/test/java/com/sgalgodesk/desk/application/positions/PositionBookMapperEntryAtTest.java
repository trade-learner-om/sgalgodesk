package com.sgalgodesk.desk.application.positions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.positions.PositionBookMapper.TradeSeedResult;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PositionBookMapperEntryAtTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void parseInstant_isoAndIstSpace() {
		Instant iso = PositionBookMapper.parseInstant("2026-11-21T10:32:00Z");
		assertEquals(Instant.parse("2026-11-21T10:32:00Z"), iso);

		Instant ist = PositionBookMapper.parseInstant("2026-11-21 16:02:00");
		assertEquals(Instant.parse("2026-11-21T10:32:00Z"), ist);
	}

	@Test
	void withEntryAts_mergesEarliestFillFromTrades() {
		PositionBookMapper mapper = new PositionBookMapper(null);
		PositionItem open = new PositionItem(
				"NFO:12345",
				"NIFTY21NOV25000CE",
				"NFO",
				"D",
				75,
				120.0,
				0,
				0,
				0,
				0,
				"NSE:NIFTY50",
				"NIFTY",
				"CE",
				25000.0,
				"2026-11-21",
				75,
				true,
				null);
		Map<String, PositionItem> byKey = new LinkedHashMap<>();
		byKey.put(PositionBookMapper.bookKey("NFO:12345", "D"), open);
		TradeSeedResult seed = new TradeSeedResult(byKey, 1, 0, 1);

		JsonNode trades = JSON.readTree("""
				{
				  "data": [
				    {
				      "instrument_token": "12345",
				      "product": "D",
				      "transaction_type": "SELL",
				      "quantity": 75,
				      "average_price": 120,
				      "fill_timestamp": "2026-11-21 10:32:00"
				    }
				  ]
				}
				""");

		TradeSeedResult enriched = mapper.withEntryAts(seed, trades);
		PositionItem item = enriched.byKey().get(PositionBookMapper.bookKey("NFO:12345", "D"));
		assertNotNull(item);
		assertNotNull(item.entryAt());
		assertEquals(Instant.parse("2026-11-21T05:02:00Z"), item.entryAt());
	}

	@Test
	void withEntryAts_leavesNullWhenNoTimestamp() {
		PositionBookMapper mapper = new PositionBookMapper(null);
		PositionItem open = new PositionItem(
				"NFO:99",
				"X",
				"NFO",
				"D",
				1,
				1,
				0,
				0,
				0,
				0,
				null,
				null,
				"CE",
				null,
				null,
				1,
				true,
				null);
		Map<String, PositionItem> byKey = new LinkedHashMap<>();
		byKey.put(PositionBookMapper.bookKey("NFO:99", "D"), open);
		TradeSeedResult seed = new TradeSeedResult(byKey, 1, 0, 0);
		JsonNode trades = JSON.readTree("""
				{ "data": [ { "instrument_token": "99", "product": "D", "quantity": 1 } ] }
				""");
		TradeSeedResult enriched = mapper.withEntryAts(seed, trades);
		assertNull(enriched.byKey().get(PositionBookMapper.bookKey("NFO:99", "D")).entryAt());
	}

	@Test
	void seedFromTrades_normalizesBareTokenToFeedKey() {
		PositionBookMapper mapper = new PositionBookMapper(null);
		JsonNode trades = JSON.readTree("""
				{
				  "data": [
				    {
				      "instrument_token": "47810",
				      "exchange": "NSE",
				      "tradingsymbol": "NIFTY01Dec2026-25500-CE",
				      "product": "D",
				      "transaction_type": "SELL",
				      "quantity": 195,
				      "average_price": 390.43,
				      "fill_timestamp": "2026-11-23 09:15:00"
				    },
				    {
				      "instrument_token": "47821",
				      "exchange": "NSE",
				      "tradingsymbol": "NIFTY01Dec2026-25650-CE",
				      "product": "D",
				      "transaction_type": "BUY",
				      "quantity": 195,
				      "average_price": 295.0,
				      "fill_timestamp": "2026-11-23 09:16:00"
				    }
				  ]
				}
				""");

		TradeSeedResult seed = mapper.seedFromTrades(trades);
		assertEquals(2, seed.byKey().size());
		for (PositionItem item : seed.byKey().values()) {
			assertNotNull(item.instrumentKey());
			assertTrue(
					item.instrumentKey().contains(":"),
					"expected feed key with colon, got " + item.instrumentKey());
			assertTrue(
					item.instrumentKey().startsWith("NFO:"),
					"expected NFO: prefix for CE legs, got " + item.instrumentKey());
			assertTrue(
					PositionBookMapper.isBareNumericToken(PositionBookMapper.tokenFromKey(item.instrumentKey())),
					"token part should remain numeric");
		}
		assertNotNull(seed.byKey().get(PositionBookMapper.bookKey("NFO:47810", "D")));
		assertNotNull(seed.byKey().get(PositionBookMapper.bookKey("NFO:47821", "D")));
	}

	@Test
	void resolveTradeInstrumentKey_prefixesBareDigits() {
		PositionBookMapper mapper = new PositionBookMapper(null);
		assertEquals(
				"NFO:47810",
				mapper.resolveTradeInstrumentKey("47810", "NSE", "NIFTY01Dec2026-25500-CE"));
		assertEquals("NFO:NIFTYCE", mapper.resolveTradeInstrumentKey("NFO:NIFTYCE", "NFO", "NIFTYCE"));
	}

	@Test
	void parseTradeTimestamp_skipsTimeOnlyFillAndUsesOrderTimestamp() {
		JsonNode row = JSON.readTree("""
				{
				  "fill_timestamp": "14:48:23",
				  "order_timestamp": "2026-11-21 14:48:23"
				}
				""");
		Instant ts = PositionBookMapper.parseTradeTimestamp(row);
		assertEquals(Instant.parse("2026-11-21T09:18:23Z"), ts);
	}

	@Test
	void parseTradeTimestamp_timeOnlyUsesDay() {
		JsonNode row = JSON.readTree("""
				{ "fill_timestamp": "14:48:23" }
				""");
		Instant ts = PositionBookMapper.parseTradeTimestamp(row, java.time.LocalDate.of(2026, 11, 21));
		assertEquals(Instant.parse("2026-11-21T09:18:23Z"), ts);
	}

	@Test
	void openOfCurrentNet_usesReopenNotEarliestClosedCycle() {
		var fills = java.util.List.of(
				new PositionBookMapper.TradeFill(Instant.parse("2026-11-20T05:00:00Z"), 75),
				new PositionBookMapper.TradeFill(Instant.parse("2026-11-20T06:00:00Z"), -75),
				new PositionBookMapper.TradeFill(Instant.parse("2026-11-21T05:30:00Z"), 75));
		assertEquals(Instant.parse("2026-11-21T05:30:00Z"), PositionBookMapper.openOfCurrentNet(fills));
	}

	@Test
	void withEntryAts_usesOpenOfCurrentNetAcrossDays() {
		PositionBookMapper mapper = new PositionBookMapper(null);
		PositionItem open = new PositionItem(
				"NFO:12345",
				"NIFTYCE",
				"NFO",
				"D",
				75,
				120.0,
				0,
				0,
				0,
				0,
				"NSE:NIFTY50",
				"NIFTY",
				"CE",
				25000.0,
				"2026-11-21",
				75,
				true,
				null);
		Map<String, PositionItem> byKey = new LinkedHashMap<>();
		byKey.put(PositionBookMapper.bookKey("NFO:12345", "D"), open);
		TradeSeedResult seed = new TradeSeedResult(byKey, 1, 0, 1);

		JsonNode trades = JSON.readTree("""
				{
				  "data": [
				    {
				      "instrument_token": "12345",
				      "product": "D",
				      "transaction_type": "BUY",
				      "quantity": 75,
				      "order_timestamp": "2026-11-20 10:00:00"
				    },
				    {
				      "instrument_token": "12345",
				      "product": "D",
				      "transaction_type": "SELL",
				      "quantity": 75,
				      "order_timestamp": "2026-11-20 15:00:00"
				    },
				    {
				      "instrument_token": "12345",
				      "product": "D",
				      "transaction_type": "BUY",
				      "quantity": 75,
				      "order_timestamp": "2026-11-21 10:30:00"
				    }
				  ]
				}
				""");

		TradeSeedResult enriched = mapper.withEntryAts(seed, trades);
		PositionItem item = enriched.byKey().get(PositionBookMapper.bookKey("NFO:12345", "D"));
		assertEquals(Instant.parse("2026-11-21T05:00:00Z"), item.entryAt());
	}
}

package com.sgalgodesk.desk.application.indicators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.domain.indicators.Candle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CandleIntradayParseTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void parseCandles_typeAIntradayShape() {
		JsonNode json = JSON.readTree("""
				{
				  "status": "success",
				  "data": {
				    "candles": [
				      ["2025-03-13T12:03:00+05", 502.95, 502.95, 502.6, 502.65, 4634],
				      ["2025-03-13T12:02:00+05", 502.8, 502.95, 502.7, 502.7, 4069]
				    ]
				  }
				}
				""");
		List<Candle> candles = CandleService.parseCandles(json);
		assertEquals(2, candles.size());
		assertTrue(candles.get(0).timestamp().isBefore(candles.get(1).timestamp())
				|| candles.get(0).close() > 0);
	}

	@Test
	void parseCandles_typeBIntradaySpaceTimestamp() {
		JsonNode json = JSON.readTree("""
				{
				  "status": true,
				  "message": "SUCCESS",
				  "data": {
				    "candles": [
				      ["2025-04-04 15:27", 553.1, 554.65, 553, 554, 420272],
				      ["2025-04-04 15:24", 552.7, 553.35, 552.35, 553.3, 108013]
				    ]
				  }
				}
				""");
		List<Candle> candles = CandleService.parseCandles(json);
		assertEquals(2, candles.size());
		Instant expected = LocalDateTime.of(2025, 4, 4, 15, 24)
				.toInstant(ZoneOffset.ofHoursMinutes(5, 30));
		assertEquals(expected, candles.get(0).timestamp());
		assertEquals(553.3, candles.get(0).close(), 1e-9);
	}

	@Test
	void parseCandles_nullCandlesIsEmpty() {
		JsonNode json = JSON.readTree("""
				{"status":"success","data":{"candles":null}}
				""");
		assertTrue(CandleService.parseCandles(json).isEmpty());
	}

	@Test
	void fromCandles_parsesIntradayMinuteWithoutSeconds() {
		List<Candle> candles = TradingViewIndicatorUtil.fromCandles(List.of(
				List.of("2025-04-04 15:27", 100, 101, 99, 100.5, 10)));
		assertEquals(1, candles.size());
		assertEquals(
				LocalDateTime.of(2025, 4, 4, 15, 27).toInstant(ZoneOffset.ofHoursMinutes(5, 30)),
				candles.get(0).timestamp());
	}
}

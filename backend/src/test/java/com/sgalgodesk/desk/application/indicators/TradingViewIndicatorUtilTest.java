package com.sgalgodesk.desk.application.indicators;

import com.sgalgodesk.desk.domain.indicators.Candle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

class TradingViewIndicatorUtilTest {

	@Test
	void fromCandles_sortsAscendingAndParsesIsoOffset() {
		List<List<Object>> raw = List.of(
				List.of("2026-11-19T11:45:00+05:30", 100.5, 101.0, 99.5, 100.0, 1000L, 50),
				List.of("2026-11-19T09:15:00+05:30", 98.0, 99.0, 97.5, 98.5, 800, 40),
				List.of("2026-11-19T10:30:00+05:30", 98.5, 100.0, 98.0, 99.5, 900, 45));

		List<Candle> candles = TradingViewIndicatorUtil.fromCandles(raw);

		assertEquals(3, candles.size());
		assertEquals(OffsetDateTime.parse("2026-11-19T09:15:00+05:30").toInstant(), candles.get(0).timestamp());
		assertEquals(OffsetDateTime.parse("2026-11-19T10:30:00+05:30").toInstant(), candles.get(1).timestamp());
		assertEquals(OffsetDateTime.parse("2026-11-19T11:45:00+05:30").toInstant(), candles.get(2).timestamp());
		assertEquals(98.5, candles.get(0).close(), 1e-9);
		assertEquals(1000L, candles.get(2).volume());
		assertEquals(50L, candles.get(2).openInterest());
	}

	@Test
	void fromCandles_rejectsNull() {
		assertThrows(IllegalArgumentException.class, () -> TradingViewIndicatorUtil.fromCandles(null));
	}

	@Test
	void calculateEma_emptyAndInvalid() {
		assertTrue(TradingViewIndicatorUtil.calculateEma(List.of(), 9).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> TradingViewIndicatorUtil.calculateEma(null, 9));
		assertThrows(IllegalArgumentException.class, () -> TradingViewIndicatorUtil.calculateEma(List.of(sample(1, 1)), 0));
	}

	@Test
	void calculateEma_fewerThanLength_allNanThenSmaSeed() {
		List<Candle> shortList = List.of(sample(1, 10), sample(2, 12), sample(3, 14));
		List<EmaPoint> ema3 = TradingViewIndicatorUtil.calculateEma(shortList, 5);
		assertEquals(3, ema3.size());
		assertTrue(Double.isNaN(ema3.get(0).value()));
		assertTrue(Double.isNaN(ema3.get(1).value()));
		assertTrue(Double.isNaN(ema3.get(2).value()));

		List<EmaPoint> ema3ok = TradingViewIndicatorUtil.calculateEma(shortList, 3);
		assertTrue(Double.isNaN(ema3ok.get(0).value()));
		assertTrue(Double.isNaN(ema3ok.get(1).value()));
		assertEquals((10 + 12 + 14) / 3.0, ema3ok.get(2).value(), 1e-12);
	}

	@Test
	void calculateEma_alphaRecurrence() {
		double[] closes = { 10, 11, 12, 13, 14, 15 };
		double[] ema = TradingViewIndicatorUtil.calculateEmaValues(closes, 3);
		assertTrue(Double.isNaN(ema[0]));
		assertTrue(Double.isNaN(ema[1]));
		assertEquals(11.0, ema[2], 1e-12);
		double alpha = 2.0 / 4.0;
		assertEquals(alpha * 13 + (1 - alpha) * 11.0, ema[3], 1e-12);
		assertEquals(alpha * 14 + (1 - alpha) * ema[3], ema[4], 1e-12);
	}

	@Test
	void calculateSupertrend_emptyAndInvalid() {
		assertTrue(TradingViewIndicatorUtil.calculateSupertrend(List.of(), 3.5, 10).isEmpty());
		assertThrows(IllegalArgumentException.class,
				() -> TradingViewIndicatorUtil.calculateSupertrend(null, 3.5, 10));
		assertThrows(IllegalArgumentException.class,
				() -> TradingViewIndicatorUtil.calculateSupertrend(List.of(sample(1, 1)), 0, 10));
		assertThrows(IllegalArgumentException.class,
				() -> TradingViewIndicatorUtil.calculateSupertrend(List.of(sample(1, 1)), 3.5, 0));
	}

	@Test
	void calculateSupertrend_warmupNanThenFlips() {
		// Construct a series that trends up then dumps so Supertrend flips DOWN -> UP -> DOWN.
		List<Candle> candles = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			double base = 100 + i * 2;
			candles.add(new Candle(Instant.ofEpochSecond(1_700_000_000L + i * 3600L),
					base, base + 1, base - 1, base + 0.5, 1000, 0));
		}
		// Sharp dump
		for (int i = 0; i < 10; i++) {
			double base = 130 - i * 4;
			candles.add(new Candle(Instant.ofEpochSecond(1_700_000_000L + (15 + i) * 3600L),
					base, base + 0.5, base - 2, base - 1.5, 1000, 0));
		}
		// Recovery rally
		for (int i = 0; i < 12; i++) {
			double base = 90 + i * 3;
			candles.add(new Candle(Instant.ofEpochSecond(1_700_000_000L + (25 + i) * 3600L),
					base, base + 2, base - 0.5, base + 1.5, 1000, 0));
		}

		List<SupertrendPoint> st = TradingViewIndicatorUtil.calculateSupertrend(candles, 3.0, 5);
		assertEquals(candles.size(), st.size());
		for (int i = 0; i < 4; i++) {
			assertTrue(Double.isNaN(st.get(i).supertrend()), "warmup bar " + i);
			assertEquals(TrendDirection.DOWN, st.get(i).direction());
		}
		assertTrue(!Double.isNaN(st.get(4).atr()));

		boolean sawUp = false;
		boolean sawDownAfterUp = false;
		boolean sawUpAgain = false;
		TrendDirection prev = null;
		for (SupertrendPoint p : st) {
			if (Double.isNaN(p.supertrend())) {
				continue;
			}
			if (prev == TrendDirection.DOWN && p.direction() == TrendDirection.UP) {
				if (!sawUp) {
					sawUp = true;
				} else {
					sawUpAgain = true;
				}
			}
			if (sawUp && prev == TrendDirection.UP && p.direction() == TrendDirection.DOWN) {
				sawDownAfterUp = true;
			}
			prev = p.direction();
		}
		assertTrue(sawUp, "expected UP flip");
		assertTrue(sawDownAfterUp, "expected UP -> DOWN flip");
		assertTrue(sawUpAgain, "expected DOWN -> UP flip after recovery");
	}

	@Test
	void csvParity_matchesExportedColumnsWithin1eMinus4() throws Exception {
		List<CsvRow> rows = loadCsv("/indicators/tradingview_parity.csv");
		List<Candle> candles = rows.stream()
				.map(r -> new Candle(r.time, r.open, r.high, r.low, r.close, 0, 0))
				.toList();

		int emaLen = 9;
		int atrPeriod = 10;
		double factor = 3.5;
		int warmup = Math.max(emaLen, atrPeriod) * 3;

		List<EmaPoint> ema = TradingViewIndicatorUtil.calculateEma(candles, emaLen);
		List<SupertrendPoint> st = TradingViewIndicatorUtil.calculateSupertrend(candles, factor, atrPeriod);

		assertEquals(rows.size(), ema.size());
		assertEquals(rows.size(), st.size());

		for (int i = warmup; i < rows.size(); i++) {
			CsvRow expected = rows.get(i);
			assertEquals(expected.ema, ema.get(i).value(), 1e-4, "EMA mismatch at row " + i);
			assertEquals(expected.supertrend, st.get(i).supertrend(), 1e-4, "Supertrend mismatch at row " + i);
		}
	}

	private static Candle sample(int idx, double close) {
		return new Candle(Instant.ofEpochSecond(1_700_000_000L + idx), close, close + 1, close - 1, close, 1, 0);
	}

	private static List<CsvRow> loadCsv(String resource) throws Exception {
		List<CsvRow> rows = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				Objects.requireNonNull(TradingViewIndicatorUtilTest.class.getResourceAsStream(resource),
						"missing " + resource),
				StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			assertTrue(header != null && header.toLowerCase().startsWith("time"));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] p = line.split(",");
				rows.add(new CsvRow(
						OffsetDateTime.parse(p[0].trim()).toInstant(),
						parseDouble(p[1]),
						parseDouble(p[2]),
						parseDouble(p[3]),
						parseDouble(p[4]),
						parseDouble(p[5]),
						parseDouble(p[6])));
			}
		}
		return rows;
	}

	private static double parseDouble(String raw) {
		String s = raw.trim();
		if (s.equalsIgnoreCase("nan") || s.isEmpty()) {
			return Double.NaN;
		}
		return Double.parseDouble(s);
	}

	private record CsvRow(Instant time, double open, double high, double low, double close, double ema,
			double supertrend) {
	}
}

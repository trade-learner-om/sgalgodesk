package com.sgalgodesk.desk.application.indicators;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.indicators.port.CandleCachePort;
import com.sgalgodesk.desk.domain.indicators.Candle;

class CandleServiceCoverageTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Test
	void coverageOk_afterHours_whenBarsReachSessionClose() {
		LocalDate day = LocalDate.of(2026, 11, 21);
		Instant from = day.atTime(LocalTime.of(9, 15)).atZone(IST).toInstant();
		// Simulate request after market close (17:00 IST)
		Instant to = day.atTime(LocalTime.of(17, 0)).atZone(IST).toInstant();

		List<Candle> bars = fiveMinuteBars(day, LocalTime.of(9, 15), LocalTime.of(15, 30));
		assertTrue(
				CandleService.coverageOk(bars, from, to, CandleCachePort.INTERVAL_5M),
				"after-hours to=now should complete against session-close clamp");
	}

	@Test
	void coverageOk_falseWhenEmpty() {
		LocalDate day = LocalDate.of(2026, 11, 21);
		Instant from = day.atTime(LocalTime.of(9, 15)).atZone(IST).toInstant();
		Instant to = day.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
		assertFalse(CandleService.coverageOk(List.of(), from, to, CandleCachePort.INTERVAL_5M));
	}

	private static List<Candle> fiveMinuteBars(LocalDate day, LocalTime start, LocalTime end) {
		List<Candle> out = new ArrayList<>();
		LocalDateTime cursor = day.atTime(start);
		LocalDateTime stop = day.atTime(end);
		while (!cursor.isAfter(stop)) {
			Instant ts = cursor.atZone(IST).toInstant();
			out.add(new Candle(ts, 100, 101, 99, 100.5, 0, 0));
			cursor = cursor.plusMinutes(5);
		}
		return out;
	}
}

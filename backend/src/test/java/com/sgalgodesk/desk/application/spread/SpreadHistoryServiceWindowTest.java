package com.sgalgodesk.desk.application.spread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.application.indicators.CandleService;

@ExtendWith(MockitoExtension.class)
class SpreadHistoryServiceWindowTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Mock
	CandleService candleService;
	@Mock
	MstockMarketCalendarService marketCalendar;

	SpreadHistoryService service;

	@BeforeEach
	void setUp() {
		service = new SpreadHistoryService(candleService, marketCalendar);
	}

	@Test
	void resolveHistoryFrom_usesFlooredEntryAtOnEntryDay() {
		LocalDate day = LocalDate.of(2026, 11, 21);
		Instant sessionOpen = day.atTime(9, 15).atZone(IST).toInstant();
		when(marketCalendar.sessionOpenInstant(day)).thenReturn(sessionOpen);

		Instant entry = day.atTime(10, 32, 10).atZone(IST).toInstant();
		Instant from = service.resolveHistoryFrom(entry.toString(), null);
		// Floored to 5m = 10:30 IST
		assertEquals(day.atTime(10, 30).atZone(IST).toInstant(), from);
	}

	@Test
	void resolveHistoryFrom_withoutEntryUsesPreviousTradingDay() {
		LocalDate today = LocalDate.of(2026, 11, 24);
		LocalDate prev = LocalDate.of(2026, 11, 21);
		Instant prevOpen = prev.atTime(9, 15).atZone(IST).toInstant();
		when(marketCalendar.previousTradingDayOnOrBefore(any(LocalDate.class))).thenReturn(prev);
		when(marketCalendar.sessionOpenInstant(prev)).thenReturn(prevOpen);

		Instant from = service.resolveHistoryFrom(null, today.toString());
		assertEquals(prevOpen, from);
	}

	@Test
	void resolveHistoryTo_whenClosedUsesLastSessionClose() {
		LocalDate last = LocalDate.of(2026, 11, 21);
		Instant close = last.atTime(15, 30).atZone(IST).toInstant();
		Instant from = last.atTime(9, 15).atZone(IST).toInstant();
		when(marketCalendar.isWithinSession(any(Instant.class))).thenReturn(false);
		when(marketCalendar.previousTradingDayOnOrBefore(any(LocalDate.class))).thenReturn(last);
		when(marketCalendar.sessionCloseInstant(last)).thenReturn(close);

		Instant to = service.resolveHistoryTo(from);
		assertEquals(close, to);
		assertTrue(to.isAfter(from));
	}

	@Test
	void parseEntryAt_acceptsIso() {
		assertEquals(
				Instant.parse("2026-11-21T05:02:00Z"),
				SpreadHistoryService.parseEntryAt("2026-11-21T05:02:00Z"));
	}
}

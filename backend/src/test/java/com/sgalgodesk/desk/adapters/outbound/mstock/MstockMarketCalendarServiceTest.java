package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService.CalendarSnapshot;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService.SessionWindow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MstockMarketCalendarServiceTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	void normalWeekdayUsesNfoWeekHours() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), holidaysJson());
		LocalDate tue = LocalDate.of(2026, 11, 17); // Tuesday
		Optional<SessionWindow> w = MstockMarketCalendarService.resolveWindow(snap, tue);
		assertTrue(w.isPresent());
		assertEquals(LocalTime.of(9, 15), w.get().open());
		assertEquals(LocalTime.of(15, 40), w.get().close());
		assertTrue(MstockMarketCalendarService.resolveWindow(snap, tue).get()
				.contains(LocalTime.of(10, 0)));
		assertFalse(w.get().contains(LocalTime.of(15, 45)));
	}

	@Test
	void specificDateZeroHoursMeansClosed() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), holidaysJson());
		LocalDate closed = LocalDate.of(2026, 11, 24);
		assertTrue(MstockMarketCalendarService.resolveWindow(snap, closed).isEmpty());
	}

	@Test
	void holidaySetClosesDayUnlessSpecial() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), holidaysJson());
		LocalDate holidayOnly = LocalDate.of(2026, 1, 26);
		assertTrue(MstockMarketCalendarService.resolveWindow(snap, holidayOnly).isEmpty());
	}

	@Test
	void specialMarketDayOverridesHolidayAndWeekHours() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), holidaysJson());
		LocalDate special = LocalDate.of(2026, 11, 8);
		Optional<SessionWindow> w = MstockMarketCalendarService.resolveWindow(snap, special);
		assertTrue(w.isPresent());
		assertEquals(LocalTime.of(14, 20), w.get().open());
		assertEquals(LocalTime.of(15, 30), w.get().close());
		assertTrue(w.get().contains(LocalTime.of(14, 30)));
		assertFalse(w.get().contains(LocalTime.of(10, 0)));
	}

	@Test
	void weekendClosedWithoutHolidayEntry() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), """
				{ "d": { "holidays": {}, "specialMarketDay": {} }, "s": "ok" }
				""");
		assertTrue(MstockMarketCalendarService.resolveWindow(snap, LocalDate.of(2026, 11, 22)).isEmpty());
	}

	@Test
	void isSessionOpenUsesWindow() throws Exception {
		CalendarSnapshot snap = parseFixtures(hoursJson(), holidaysJson());
		// Inject via resolve + manual LocalDateTime check mirroring service
		LocalDateTime inSession = LocalDateTime.of(2026, 11, 17, 10, 0);
		LocalDateTime afterClose = LocalDateTime.of(2026, 11, 17, 15, 45);
		assertTrue(MstockMarketCalendarService.resolveWindow(snap, inSession.toLocalDate())
				.map(w -> w.contains(inSession.toLocalTime())).orElse(false));
		assertFalse(MstockMarketCalendarService.resolveWindow(snap, afterClose.toLocalDate())
				.map(w -> w.contains(afterClose.toLocalTime())).orElse(false));
	}

	@Test
	void fallbackSnapshotMonFri915To1530() {
		CalendarSnapshot snap = MstockMarketCalendarService.fallbackSnapshot(Instant.now().plusSeconds(60));
		Optional<SessionWindow> mon = MstockMarketCalendarService.resolveWindow(
				snap, LocalDate.of(2026, 11, 23));
		assertTrue(mon.isPresent());
		assertEquals(LocalTime.of(9, 15), mon.get().open());
		assertEquals(LocalTime.of(15, 30), mon.get().close());
	}

	private static CalendarSnapshot parseFixtures(String hours, String holidays) throws Exception {
		JsonNode h = MAPPER.readTree(hours);
		JsonNode hol = MAPPER.readTree(holidays);
		return MstockMarketCalendarService.parse(h, hol, Instant.now().plusSeconds(3600));
	}

	private static String hoursJson() {
		return """
				{
				  "d": {
				    "NFO": {
				      "weekDays": [
				        {"open":"09:15:00","close":"15:40:00","dayofweek":1},
				        {"open":"09:15:00","close":"15:40:00","dayofweek":2},
				        {"open":"09:15:00","close":"15:40:00","dayofweek":3},
				        {"open":"09:15:00","close":"15:40:00","dayofweek":4},
				        {"open":"09:15:00","close":"15:40:00","dayofweek":5}
				      ],
				      "specificDate": [
				        {"date":"2026-11-08","open":"14:20:00","close":"15:40:00"},
				        {"date":"2026-11-24","open":"00:00:00","close":"00:00:00"}
				      ]
				    }
				  },
				  "s": "ok"
				}
				""";
	}

	private static String holidaysJson() {
		return """
				{
				  "d": {
				    "holidays": {
				      "2026": ["2026-01-26","2026-11-08","2026-11-24"]
				    },
				    "specialMarketDay": {
				      "2026": [
				        {"date":"2026-11-08","open":"14:20:00","close":"15:30:00"}
				      ]
				    }
				  },
				  "s": "ok"
				}
				""";
	}
}

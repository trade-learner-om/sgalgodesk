package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * NFO session windows from MStock optionstrategy market-hours + NSE holiday feed.
 * Cached in memory; falls back to Mon–Fri 09:15–15:30 when feeds fail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MstockMarketCalendarService {

	public static final String SEGMENT_NFO = "NFO";
	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final Duration CACHE_TTL = Duration.ofHours(6);
	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
	private static final LocalTime FALLBACK_OPEN = LocalTime.of(9, 15);
	private static final LocalTime FALLBACK_CLOSE = LocalTime.of(15, 30);

	private final MstockApiClient mstockApiClient;

	private final AtomicReference<CalendarSnapshot> cache = new AtomicReference<>();
	private final AtomicBoolean loggedFetchFailure = new AtomicBoolean(false);

	public record SessionWindow(LocalTime open, LocalTime close) {
		boolean contains(LocalTime t) {
			return t != null && !t.isBefore(open) && !t.isAfter(close);
		}

		boolean isClosed() {
			return open.equals(LocalTime.MIDNIGHT) && close.equals(LocalTime.MIDNIGHT);
		}
	}

	record CalendarSnapshot(
			Map<Integer, LocalTime[]> weekDaysByDow,
			Map<LocalDate, SessionWindow> specificDates,
			Set<LocalDate> holidays,
			Map<LocalDate, SessionWindow> specialMarketDays,
			Instant expiresAt) {
	}

	public boolean isTradingDay(LocalDate date) {
		return sessionWindow(date).isPresent();
	}

	public Optional<SessionWindow> sessionWindow(LocalDate date) {
		if (date == null) {
			return Optional.empty();
		}
		CalendarSnapshot snap = ensureSnapshot();
		return resolveWindow(snap, date);
	}

	public boolean isSessionOpen(LocalDateTime whenIst) {
		if (whenIst == null) {
			return false;
		}
		return sessionWindow(whenIst.toLocalDate())
				.map(w -> w.contains(whenIst.toLocalTime()))
				.orElse(false);
	}

	public boolean isSessionOpenNow() {
		return isSessionOpen(LocalDateTime.now(IST));
	}

	/** Most recent NFO trading day on or before {@code date} (inclusive). */
	public LocalDate previousTradingDayOnOrBefore(LocalDate date) {
		if (date == null) {
			date = LocalDate.now(IST);
		}
		LocalDate d = date;
		for (int i = 0; i < 60; i++) {
			if (isTradingDay(d)) {
				return d;
			}
			d = d.minusDays(1);
		}
		return date;
	}

	/** Next NFO trading day strictly after {@code date}. Falls back to {@code date.plusDays(1)} if none found. */
	public LocalDate nextTradingDayAfter(LocalDate date) {
		LocalDate d = date != null ? date : LocalDate.now(IST);
		for (int i = 1; i <= 60; i++) {
			LocalDate candidate = d.plusDays(i);
			if (isTradingDay(candidate)) {
				return candidate;
			}
		}
		return d.plusDays(1);
	}

	/** True when the instant falls inside that day's NFO session window. */
	public boolean isWithinSession(Instant instant) {
		if (instant == null) {
			return false;
		}
		LocalDateTime ldt = LocalDateTime.ofInstant(instant, IST);
		return isSessionOpen(ldt);
	}

	public Instant sessionOpenInstant(LocalDate date) {
		return sessionWindow(date)
				.map(w -> date.atTime(w.open()).atZone(IST).toInstant())
				.orElse(date.atTime(FALLBACK_OPEN).atZone(IST).toInstant());
	}

	public Instant sessionCloseInstant(LocalDate date) {
		return sessionWindow(date)
				.map(w -> date.atTime(w.close()).atZone(IST).toInstant())
				.orElse(date.atTime(FALLBACK_CLOSE).atZone(IST).toInstant());
	}

	/** Package-visible for tests: resolve without network. */
	static Optional<SessionWindow> resolveWindow(CalendarSnapshot snap, LocalDate date) {
		if (snap == null || date == null) {
			return Optional.empty();
		}
		SessionWindow special = snap.specialMarketDays().get(date);
		if (special != null) {
			return special.isClosed() ? Optional.empty() : Optional.of(special);
		}
		SessionWindow specific = snap.specificDates().get(date);
		if (specific != null) {
			return specific.isClosed() ? Optional.empty() : Optional.of(specific);
		}
		if (snap.holidays().contains(date)) {
			return Optional.empty();
		}
		int dow = date.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
		LocalTime[] hours = snap.weekDaysByDow().get(dow);
		if (hours == null || hours.length < 2) {
			return Optional.empty();
		}
		SessionWindow weekly = new SessionWindow(hours[0], hours[1]);
		return weekly.isClosed() ? Optional.empty() : Optional.of(weekly);
	}

	static CalendarSnapshot parse(JsonNode marketHours, JsonNode holidays, Instant expiresAt) {
		Map<Integer, LocalTime[]> weekDays = new HashMap<>();
		Map<LocalDate, SessionWindow> specific = new HashMap<>();
		JsonNode nfo = marketHours != null ? marketHours.path("d").path(SEGMENT_NFO) : null;
		if (nfo != null && !nfo.isMissingNode() && !nfo.isNull()) {
			JsonNode week = nfo.path("weekDays");
			if (week.isArray()) {
				for (JsonNode row : week) {
					int dow = row.path("dayofweek").asInt(0);
					LocalTime open = parseTime(row.path("open").asString(null));
					LocalTime close = parseTime(row.path("close").asString(null));
					if (dow >= 1 && dow <= 7 && open != null && close != null) {
						weekDays.put(dow, new LocalTime[] { open, close });
					}
				}
			}
			JsonNode specs = nfo.path("specificDate");
			if (specs.isArray()) {
				for (JsonNode row : specs) {
					LocalDate d = parseDate(row.path("date").asString(null));
					LocalTime open = parseTime(row.path("open").asString(null));
					LocalTime close = parseTime(row.path("close").asString(null));
					if (d != null && open != null && close != null) {
						specific.put(d, new SessionWindow(open, close));
					}
				}
			}
		}

		Set<LocalDate> holidaySet = new HashSet<>();
		Map<LocalDate, SessionWindow> special = new HashMap<>();
		JsonNode d = holidays != null ? holidays.path("d") : null;
		if (d != null && !d.isMissingNode()) {
			JsonNode holRoot = d.path("holidays");
			if (holRoot.isObject()) {
				holRoot.properties().forEach(e -> {
					JsonNode arr = e.getValue();
					if (arr != null && arr.isArray()) {
						for (JsonNode n : arr) {
							LocalDate hd = parseDate(n.asString(null));
							if (hd != null) {
								holidaySet.add(hd);
							}
						}
					}
				});
			}
			JsonNode specialRoot = d.path("specialMarketDay");
			if (specialRoot.isObject()) {
				specialRoot.properties().forEach(e -> {
					JsonNode arr = e.getValue();
					if (arr != null && arr.isArray()) {
						for (JsonNode row : arr) {
							LocalDate sd = parseDate(row.path("date").asString(null));
							LocalTime open = parseTime(row.path("open").asString(null));
							LocalTime close = parseTime(row.path("close").asString(null));
							if (sd != null && open != null && close != null) {
								special.put(sd, new SessionWindow(open, close));
							}
						}
					}
				});
			}
		}

		if (weekDays.isEmpty()) {
			for (int dow = 1; dow <= 5; dow++) {
				weekDays.put(dow, new LocalTime[] { FALLBACK_OPEN, FALLBACK_CLOSE });
			}
		}
		return new CalendarSnapshot(weekDays, specific, holidaySet, special, expiresAt);
	}

	static CalendarSnapshot fallbackSnapshot(Instant expiresAt) {
		Map<Integer, LocalTime[]> weekDays = new HashMap<>();
		for (int dow = 1; dow <= 5; dow++) {
			weekDays.put(dow, new LocalTime[] { FALLBACK_OPEN, FALLBACK_CLOSE });
		}
		return new CalendarSnapshot(weekDays, Map.of(), Set.of(), Map.of(), expiresAt);
	}

	private CalendarSnapshot ensureSnapshot() {
		CalendarSnapshot current = cache.get();
		Instant now = Instant.now();
		if (current != null && now.isBefore(current.expiresAt())) {
			return current;
		}
		synchronized (this) {
			current = cache.get();
			if (current != null && Instant.now().isBefore(current.expiresAt())) {
				return current;
			}
			try {
				JsonNode hours = mstockApiClient.getMarketHours().block(FETCH_TIMEOUT);
				JsonNode holidays = mstockApiClient.getHolidays("NSE").block(FETCH_TIMEOUT);
				CalendarSnapshot parsed = parse(hours, holidays, Instant.now().plus(CACHE_TTL));
				cache.set(parsed);
				loggedFetchFailure.set(false);
				return parsed;
			}
			catch (Exception ex) {
				if (loggedFetchFailure.compareAndSet(false, true)) {
					log.warn("MStock market calendar fetch failed; using fallback session: {}", ex.toString());
				}
				if (current != null) {
					// Keep stale snapshot briefly so we do not hammer the feed.
					CalendarSnapshot extended = new CalendarSnapshot(
							current.weekDaysByDow(),
							current.specificDates(),
							current.holidays(),
							current.specialMarketDays(),
							Instant.now().plus(Duration.ofMinutes(15)));
					cache.set(extended);
					return extended;
				}
				CalendarSnapshot fallback = fallbackSnapshot(Instant.now().plus(Duration.ofMinutes(15)));
				cache.set(fallback);
				return fallback;
			}
		}
	}

	static LocalTime parseTime(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			String t = raw.trim();
			if (t.length() == 5) {
				return LocalTime.parse(t);
			}
			return LocalTime.parse(t);
		}
		catch (Exception ex) {
			return null;
		}
	}

	static LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (Exception ex) {
			return null;
		}
	}
}

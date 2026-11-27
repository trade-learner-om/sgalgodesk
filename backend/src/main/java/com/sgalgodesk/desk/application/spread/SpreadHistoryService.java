package com.sgalgodesk.desk.application.spread;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.application.indicators.CandleService;
import com.sgalgodesk.desk.domain.indicators.Candle;
import com.sgalgodesk.desk.domain.spread.SpreadCalculator;
import com.sgalgodesk.desk.domain.spread.SpreadLeg;
import com.sgalgodesk.desk.domain.spread.SpreadMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpreadHistoryService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final long BAR_SEC = 300L;

	private final CandleService candleService;
	private final MstockMarketCalendarService marketCalendar;

	public Mono<SpreadHistoryResponse> history(UUID userId, SpreadHistoryRequest request) {
		List<SpreadLeg> legs = toLegs(request.legs());
		if (legs.isEmpty()) {
			return Mono.just(new SpreadHistoryResponse(List.of(), 1, "debit", false, null));
		}

		Instant from = resolveHistoryFrom(request.entryAt(), request.openDay());
		Instant to = resolveHistoryTo(from);
		if (!to.isAfter(from)) {
			String msg = marketCalendar.isTradingDay(LocalDate.now(IST))
					? "No candle window yet for this session"
					: "Market closed — no session candles to show";
			return Mono.just(new SpreadHistoryResponse(List.of(), 1, "debit", false, msg));
		}

		Set<String> keys = new HashSet<>();
		for (SpreadLeg leg : legs) {
			keys.add(leg.instrumentKey());
		}

		Instant fromFinal = from;
		Instant toFinal = to;
		log.debug("Spread history start legs={} keys={} from={} to={}",
				legs.size(), keys, fromFinal, toFinal);
		AtomicBoolean incomplete = new AtomicBoolean(false);
		AtomicReference<String> emptyLegHint = new AtomicReference<>();
		return Flux.fromIterable(keys)
				.flatMap(key -> candleService.ensureFiveMinuteCandlesAwaitingFill(userId, key, fromFinal, toFinal)
						.doOnNext(snap -> {
							if (snap.incomplete()) {
								incomplete.set(true);
							}
							if (snap.candles() == null || snap.candles().isEmpty()) {
								emptyLegHint.compareAndSet(null, "No candles for " + key);
								incomplete.set(true);
							}
							log.debug("Spread history leg candles key={} count={} incomplete={}",
									key, snap.candles().size(), snap.incomplete());
						})
						.map(snap -> Map.entry(key, snap.candles()))
						.onErrorResume(ex -> {
							log.warn("Spread history candles failed for key={}: {}", key, ex.toString());
							incomplete.set(true);
							emptyLegHint.compareAndSet(null, "Candle fetch failed for " + key);
							return Mono.just(Map.entry(key, List.<Candle>of()));
						}))
				.collectMap(Map.Entry::getKey, Map.Entry::getValue)
				.map(byKey -> {
					SpreadHistoryResponse res = buildSeries(
							legs, byKey, fromFinal, toFinal, incomplete.get(), emptyLegHint.get());
					log.debug("Spread history done points={} lots={} kind={} incomplete={} message={}",
							res.points().size(), res.lots(), res.kind(), res.incomplete(), res.message());
					return res;
				})
				.onErrorResume(ex -> {
					log.warn("Spread history failed entirely: {}", ex.toString());
					return Mono.just(new SpreadHistoryResponse(
							List.of(), 1, "debit", true, "Failed to load candle history"));
				});
	}

	/**
	 * From floored entryAt (clamped to that day's session open), or previous NFO session open
	 * when entryAt is missing.
	 */
	Instant resolveHistoryFrom(String entryAtRaw, String openDayHint) {
		Instant entry = parseEntryAt(entryAtRaw);
		if (entry != null) {
			LocalDate entryDay = entry.atZone(IST).toLocalDate();
			Instant sessionOpen = marketCalendar.sessionOpenInstant(entryDay);
			long floored = (entry.getEpochSecond() / BAR_SEC) * BAR_SEC;
			Instant flooredEntry = Instant.ofEpochSecond(floored);
			return flooredEntry.isAfter(sessionOpen) ? flooredEntry : sessionOpen;
		}
		LocalDate hintDay = null;
		if (openDayHint != null && !openDayHint.isBlank()) {
			try {
				hintDay = LocalDate.parse(openDayHint.trim());
			}
			catch (Exception ignored) {
				hintDay = null;
			}
		}
		LocalDate base = hintDay != null ? hintDay : LocalDate.now(IST);
		LocalDate trading = marketCalendar.previousTradingDayOnOrBefore(base);
		return marketCalendar.sessionOpenInstant(trading);
	}

	/**
	 * Through now if inside an open session; otherwise through the last completed session close
	 * on or before today (so holiday "now" still shows prior-day candles).
	 */
	Instant resolveHistoryTo(Instant from) {
		Instant now = Instant.now();
		LocalDate today = LocalDate.now(IST);
		if (marketCalendar.isWithinSession(now)) {
			return now.isBefore(from) ? from : now;
		}
		LocalDate lastSessionDay = marketCalendar.previousTradingDayOnOrBefore(today);
		Instant close = marketCalendar.sessionCloseInstant(lastSessionDay);
		if (close.isBefore(from)) {
			// Entry is after last close (e.g. after-hours same day) — allow up to now for empty UX.
			return now.isBefore(from) ? from : now;
		}
		return close;
	}

	private SpreadHistoryResponse buildSeries(
			List<SpreadLeg> legs,
			Map<String, List<Candle>> byKey,
			Instant from,
			Instant to,
			boolean incomplete,
			String emptyLegHint) {
		Map<String, Map<Long, Candle>> bars = new HashMap<>();
		TreeSet<Long> times = new TreeSet<>();
		for (Map.Entry<String, List<Candle>> e : byKey.entrySet()) {
			Map<Long, Candle> m = new HashMap<>();
			for (Candle c : e.getValue()) {
				long sec = c.timestamp().getEpochSecond();
				long bucket = (sec / BAR_SEC) * BAR_SEC;
				if (bucket < from.getEpochSecond() || bucket > to.getEpochSecond()) {
					continue;
				}
				LocalDateTime bucketLdt = Instant.ofEpochSecond(bucket).atZone(IST).toLocalDateTime();
				if (!marketCalendar.isSessionOpen(bucketLdt)) {
					continue;
				}
				m.put(bucket, c);
				times.add(bucket);
			}
			bars.put(e.getKey(), m);
		}

		List<SpreadHistoryPoint> points = new ArrayList<>();
		Map<String, Candle> lastKnown = new HashMap<>();
		String kind = "debit";
		int lots = 1;
		for (long bucket : times) {
			boolean allPresent = true;
			for (SpreadLeg leg : legs) {
				Map<Long, Candle> series = bars.get(leg.instrumentKey());
				Candle bar = series != null ? series.get(bucket) : null;
				if (bar != null && bar.close() > 0) {
					lastKnown.put(leg.instrumentKey(), bar);
				}
				else if (!lastKnown.containsKey(leg.instrumentKey())) {
					allPresent = false;
				}
			}
			if (!allPresent || lastKnown.size() < legs.size()) {
				continue;
			}

			double openMark = markAt(legs, lastKnown, Candle::open);
			double highMark = markAt(legs, lastKnown, Candle::high);
			double lowMark = markAt(legs, lastKnown, Candle::low);
			double closeMark = markAt(legs, lastKnown, Candle::close);
			if (Double.isNaN(openMark) || Double.isNaN(closeMark)) {
				continue;
			}
			double hi = maxFinite(openMark, highMark, lowMark, closeMark);
			double lo = minFinite(openMark, highMark, lowMark, closeMark);

			SpreadMetrics closeMetrics = SpreadCalculator.computeMetrics(legs, prices(lastKnown, Candle::close));
			if (closeMetrics == null) {
				continue;
			}
			kind = closeMetrics.kind();
			lots = closeMetrics.lots();
			points.add(new SpreadHistoryPoint(
					bucket * 1000L,
					closeMetrics.runningPnl1Lot(),
					closeMetrics.runningPnlFull(),
					closeMetrics.spreadMark1Lot(),
					closeMetrics.combinedPremium1Lot(),
					closeMetrics.lots(),
					closeMetrics.kind(),
					openMark,
					hi,
					lo,
					closeMark));
		}

		String message = null;
		boolean stillIncomplete = incomplete;
		if (points.isEmpty()) {
			if (emptyLegHint != null) {
				message = emptyLegHint;
			}
			else if (!marketCalendar.isTradingDay(LocalDate.now(IST))
					&& !hasAnyTradingSessionBetween(from, to)) {
				message = "Market closed — no session candles to show";
				stillIncomplete = false;
			}
			else if (!marketCalendar.isTradingDay(LocalDate.now(IST))) {
				message = "No candle history in range (market closed today)";
				stillIncomplete = false;
			}
		}
		return new SpreadHistoryResponse(List.copyOf(points), lots, kind, stillIncomplete, message);
	}

	private boolean hasAnyTradingSessionBetween(Instant from, Instant to) {
		LocalDate d = from.atZone(IST).toLocalDate();
		LocalDate end = to.atZone(IST).toLocalDate();
		while (!d.isAfter(end)) {
			if (marketCalendar.isTradingDay(d)) {
				return true;
			}
			d = d.plusDays(1);
		}
		return false;
	}

	/** Candle OHLC Y-axis uses live spread combined price (mark), not PnL. */
	private static double markAt(List<SpreadLeg> legs, Map<String, Candle> lastKnown, ToDoubleFunction<Candle> extractor) {
		SpreadMetrics m = SpreadCalculator.computeMetrics(legs, prices(lastKnown, extractor));
		return m != null ? m.spreadMark1Lot() : Double.NaN;
	}

	private static Map<String, Double> prices(Map<String, Candle> lastKnown, ToDoubleFunction<Candle> extractor) {
		Map<String, Double> out = new HashMap<>();
		for (Map.Entry<String, Candle> e : lastKnown.entrySet()) {
			double v = extractor.applyAsDouble(e.getValue());
			if (v > 0) {
				out.put(e.getKey(), v);
			}
		}
		return out;
	}

	private static double maxFinite(double... vals) {
		double m = Double.NEGATIVE_INFINITY;
		for (double v : vals) {
			if (!Double.isNaN(v) && v > m) {
				m = v;
			}
		}
		return m == Double.NEGATIVE_INFINITY ? 0 : m;
	}

	private static double minFinite(double... vals) {
		double m = Double.POSITIVE_INFINITY;
		for (double v : vals) {
			if (!Double.isNaN(v) && v < m) {
				m = v;
			}
		}
		return m == Double.POSITIVE_INFINITY ? 0 : m;
	}

	static Instant parseEntryAt(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(raw.trim());
		}
		catch (Exception ignored) {
			try {
				return java.time.OffsetDateTime.parse(raw.trim()).toInstant();
			}
			catch (Exception ignored2) {
				return null;
			}
		}
	}

	private static List<SpreadLeg> toLegs(List<SpreadHistoryLeg> legs) {
		if (legs == null) {
			return List.of();
		}
		List<SpreadLeg> out = new ArrayList<>();
		for (SpreadHistoryLeg l : legs) {
			if (l == null || l.instrumentKey() == null || l.instrumentKey().isBlank()) {
				continue;
			}
			String sideRaw = l.side() != null ? l.side().toUpperCase() : "BUY";
			int side = "SELL".equals(sideRaw) || "S".equals(sideRaw) ? -1 : 1;
			int lotSize = l.lotSize() != null && l.lotSize() > 0 ? l.lotSize() : 1;
			int lots = l.lots() != null && l.lots() > 0 ? l.lots() : 1;
			int qty = l.quantity() != null && l.quantity() != 0
					? Math.abs(l.quantity())
					: lotSize * lots;
			double entry = l.entryPrice() != null ? l.entryPrice() : 0;
			out.add(new SpreadLeg(l.instrumentKey().trim(), side, qty, lotSize, lots, entry));
		}
		return List.copyOf(out);
	}

	public record SpreadHistoryLeg(
			String instrumentKey,
			String side,
			Integer quantity,
			Integer lotSize,
			Integer lots,
			Double entryPrice) {
	}

	public record SpreadHistoryRequest(List<SpreadHistoryLeg> legs, String openDay, String entryAt) {
	}

	public record SpreadHistoryPoint(
			long ts,
			double runningPnl1Lot,
			double runningPnl,
			double spreadMark,
			double combinedPremium,
			int lots,
			String kind,
			double open,
			double high,
			double low,
			double close) {
	}

	public record SpreadHistoryResponse(
			List<SpreadHistoryPoint> points,
			int lots,
			String kind,
			boolean incomplete,
			String message) {
	}
}

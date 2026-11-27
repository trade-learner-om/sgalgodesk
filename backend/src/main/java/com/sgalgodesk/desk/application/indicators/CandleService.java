package com.sgalgodesk.desk.application.indicators;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockApiClient;
import com.sgalgodesk.desk.application.indicators.port.CandleCachePort;
import com.sgalgodesk.desk.domain.indicators.Candle;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandleService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
	private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
	/** How long to suppress repeat MStock fills after an empty A+B result. */
	private static final long NEGATIVE_CACHE_MS = 60_000L;
	/** MStock Type B historical expects {@code yyyy-MM-dd HH:mm}. */
	private static final DateTimeFormatter MSTOCK_DT_B = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	/** MStock Type A historical expects {@code yyyy-MM-dd HH:mm:ss}. */
	private static final DateTimeFormatter MSTOCK_DT_A = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	/** Instruments touched recently — used by backfill scheduler. */
	private final ConcurrentHashMap<String, Long> recentlyRequested = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, UUID> lastUserByInstrument = new ConcurrentHashMap<>();
	/** Deduped in-flight background MStock fills per instrument+interval. */
	private final ConcurrentHashMap<String, Mono<Void>> inflightFills = new ConcurrentHashMap<>();
	/** Until-epoch-ms when an empty A+B fill should not be retried. */
	private final ConcurrentHashMap<String, Long> emptyUntilMs = new ConcurrentHashMap<>();

	private final MstockApiClient mstockApiClient;
	private final MstockInstrumentService mstockInstrumentService;
	private final BrokerAccess brokerAccess;
	private final BrokerSessionService brokerSessionService;
	private final CandleCachePort candleCache;

	/** Redis snapshot plus whether a background MStock fill was/was needed. */
	public record CandleSnapshot(List<Candle> candles, boolean incomplete) {
	}

	public Mono<List<Candle>> fetchCandles(UUID userId, String instrumentKey, String unit, String interval, String from, String to) {
		LocalDate toDate = to != null && !to.isBlank() ? LocalDate.parse(to) : LocalDate.now(IST);
		LocalDate fromDate = from != null && !from.isBlank()
				? LocalDate.parse(from)
				: toDate.minusMonths(3);
		String intervalLabel = resolveIntervalLabel(unit, interval);
		return fetchFromMstock(userId, instrumentKey, intervalLabel, fromDate, toDate);
	}

	public Mono<List<Candle>> fetchMerged75m(UUID userId, String instrumentKey) {
		return fetchCandles(userId, instrumentKey, "minutes", "75", null, null);
	}

	/**
	 * Redis-first 1m candles: return current cache immediately; schedule MStock fill if incomplete.
	 */
	public Mono<CandleSnapshot> ensureOneMinuteCandles(UUID userId, String instrumentKey, Instant from, Instant to) {
		return ensureCandles(userId, instrumentKey, "ONE_MINUTE", CandleCachePort.INTERVAL_1M, from, to);
	}

	public Mono<CandleSnapshot> ensureFiveMinuteCandles(UUID userId, String instrumentKey, Instant from, Instant to) {
		return ensureCandles(userId, instrumentKey, "FIVE_MINUTE", CandleCachePort.INTERVAL_5M, from, to);
	}

	/**
	 * Await MStock 5m fill + Redis upsert (used by spread history so cold cache returns bars).
	 * Propagates broker/Redis failures instead of silent empty success.
	 */
	public Mono<Void> fillFiveMinuteCandles(UUID userId, String instrumentKey, Instant from, Instant to) {
		recentlyRequested.put(instrumentKey, System.currentTimeMillis());
		lastUserByInstrument.put(instrumentKey, userId);
		return fillMono(userId, instrumentKey, "FIVE_MINUTE", CandleCachePort.INTERVAL_5M, from, to, true);
	}

	/**
	 * Await fill when coverage is incomplete, then reread Redis (no second background schedule).
	 */
	public Mono<CandleSnapshot> ensureFiveMinuteCandlesAwaitingFill(
			UUID userId, String instrumentKey, Instant from, Instant to) {
		recentlyRequested.put(instrumentKey, System.currentTimeMillis());
		lastUserByInstrument.put(instrumentKey, userId);
		return candleCache.getRange(instrumentKey, CandleCachePort.INTERVAL_5M, from, to)
				.flatMap(cached -> {
					List<Candle> range = filterRange(cached, from, to);
					boolean ok = coverageOk(cached, from, to, CandleCachePort.INTERVAL_5M);
					if (ok) {
						return Mono.just(new CandleSnapshot(range, false));
					}
					return fillFiveMinuteCandles(userId, instrumentKey, from, to)
							.then(candleCache.getRange(instrumentKey, CandleCachePort.INTERVAL_5M, from, to)
									.map(after -> {
										List<Candle> filled = filterRange(after, from, to);
										boolean covered = coverageOk(after, from, to, CandleCachePort.INTERVAL_5M);
										return new CandleSnapshot(filled, !covered);
									}));
				});
	}

	private Mono<CandleSnapshot> ensureCandles(
			UUID userId,
			String instrumentKey,
			String mstockInterval,
			String redisInterval,
			Instant from,
			Instant to) {
		recentlyRequested.put(instrumentKey, System.currentTimeMillis());
		lastUserByInstrument.put(instrumentKey, userId);
		log.debug("Candle ensure {} start key={} from={} to={} userId={}",
				redisInterval, instrumentKey, from, to, userId);
		return candleCache.getRange(instrumentKey, redisInterval, from, to)
				.map(cached -> {
					List<Candle> range = filterRange(cached, from, to);
					boolean ok = coverageOk(cached, from, to, redisInterval);
					log.debug("Candle Redis range key={} interval={} cached={} coverageOk={}",
							instrumentKey, redisInterval, cached.size(), ok);
					if (!ok) {
						log.debug("Candle coverage miss for {} {}, scheduling background MStock {}",
								instrumentKey, redisInterval, mstockInterval);
						scheduleBackgroundFill(userId, instrumentKey, mstockInterval, redisInterval, from, to);
					}
					return new CandleSnapshot(range, !ok);
				});
	}

	/**
	 * Await MStock fill + Redis upsert (used by scheduled backfill).
	 */
	public Mono<Void> fillOneMinuteCandles(UUID userId, String instrumentKey, Instant from, Instant to) {
		recentlyRequested.put(instrumentKey, System.currentTimeMillis());
		lastUserByInstrument.put(instrumentKey, userId);
		return fillMono(userId, instrumentKey, "ONE_MINUTE", CandleCachePort.INTERVAL_1M, from, to, false);
	}

	private void scheduleBackgroundFill(
			UUID userId,
			String instrumentKey,
			String mstockInterval,
			String redisInterval,
			Instant from,
			Instant to) {
		fillMono(userId, instrumentKey, mstockInterval, redisInterval, from, to, false)
				.subscribe(
						v -> { },
						ex -> log.warn("Background {} fill failed for {}: {}",
								redisInterval, instrumentKey, ex.toString()));
	}

	/**
	 * @param failLoud when true (awaited spread history), empty broker data / Redis errors propagate;
	 *                 when false (scheduler), swallow and negative-cache empties.
	 */
	private Mono<Void> fillMono(
			UUID userId,
			String instrumentKey,
			String mstockInterval,
			String redisInterval,
			Instant from,
			Instant to,
			boolean failLoud) {
		String fillKey = redisInterval + ":" + instrumentKey + (failLoud ? ":strict" : "");
		long now = System.currentTimeMillis();
		Long until = emptyUntilMs.get(redisInterval + ":" + instrumentKey);
		if (!failLoud && until != null && until > now) {
			log.debug("Candle negative-cache skip key={} untilMs={}", fillKey, until);
			return Mono.empty();
		}
		return inflightFills.computeIfAbsent(fillKey, key -> fetchFromMstock(userId, instrumentKey, mstockInterval, from, to)
				.flatMap(fetched -> {
					log.debug("Candle fetch key={} interval={} fetched={} failLoud={}",
							instrumentKey, redisInterval, fetched.size(), failLoud);
					if (fetched.isEmpty()) {
						String cacheKey = redisInterval + ":" + instrumentKey;
						emptyUntilMs.put(cacheKey, System.currentTimeMillis() + NEGATIVE_CACHE_MS);
						if (failLoud) {
							return Mono.error(new BrokerUnavailable(
									"MStock returned no candles for " + instrumentKey
											+ " interval=" + mstockInterval
											+ " (intraday+historical empty)"));
						}
						log.debug("Candle empty; negative-cache {}ms key={}", NEGATIVE_CACHE_MS, cacheKey);
						return Mono.empty();
					}
					emptyUntilMs.remove(redisInterval + ":" + instrumentKey);
					return candleCache.upsert(instrumentKey, redisInterval, fetched);
				})
				.then()
				.doFinally(signal -> inflightFills.remove(key))
				.onErrorResume(ex -> {
					if (failLoud) {
						return Mono.error(ex);
					}
					log.warn("{} background fill failed for {}: {}", redisInterval, instrumentKey, ex.toString());
					return Mono.empty();
				})
				.cache());
	}

	/**
	 * Append newest 1m bars for instruments requested in the last few hours.
	 */
	public Mono<Void> backfillRecent(Instant from, Instant to) {
		long cutoff = System.currentTimeMillis() - Duration.ofHours(6).toMillis();
		List<Map.Entry<String, UUID>> work = recentlyRequested.entrySet().stream()
				.filter(e -> e.getValue() >= cutoff)
				.map(e -> Map.entry(e.getKey(), lastUserByInstrument.get(e.getKey())))
				.filter(e -> e.getValue() != null)
				.toList();
		if (work.isEmpty()) {
			return Mono.empty();
		}
		return reactor.core.publisher.Flux.fromIterable(work)
				.concatMap(e -> fillOneMinuteCandles(e.getValue(), e.getKey(), from, to)
						.then(fillMono(e.getValue(), e.getKey(), "FIVE_MINUTE", CandleCachePort.INTERVAL_5M, from, to, false)))
				.then();
	}

	private Mono<List<Candle>> fetchFromMstock(
			UUID userId,
			String instrumentKey,
			String intervalLabel,
			LocalDate fromDate,
			LocalDate toDate) {
		Instant from = fromDate.atTime(SESSION_OPEN).atZone(IST).toInstant();
		Instant to = toDate.atTime(SESSION_CLOSE).atZone(IST).toInstant();
		return fetchFromMstock(userId, instrumentKey, intervalLabel, from, to);
	}

	private Mono<List<Candle>> fetchFromMstock(
			UUID userId,
			String instrumentKey,
			String intervalLabel,
			Instant from,
			Instant to) {
		Instant clampedTo = clampToSession(to);
		Instant clampedFrom = clampFromSession(from, clampedTo);
		String typeBInterval = intervalLabel;
		String typeAInterval = toTypeAInterval(intervalLabel);
		String fromB = formatMstockB(clampedFrom);
		String toB = formatMstockB(clampedTo);
		String fromA = formatMstockA(clampedFrom);
		String toA = formatMstockA(clampedTo);
		boolean needIntraday = intersectsTodaySession(clampedFrom, clampedTo);
		boolean needHistorical = spansPriorSessionDay(clampedFrom);
		return mstockInstrumentService.ensureLoaded(userId)
				.then(brokerAccess.requireActive(userId))
				.flatMap(ctx -> {
					String token = resolveNumericToken(instrumentKey);
					if (token == null) {
						log.warn("No numeric symboltoken for {}; cannot fetch candles", instrumentKey);
						return Mono.just(List.<Candle>of());
					}
					String prefix = keyExchangePrefix(instrumentKey);
					String primary = historicalExchangePrimary(instrumentKey);
					String alternate = historicalExchangeAlternate(instrumentKey);
					String finalToken = token;
					String apiKey = ctx.apiKeyOrClientId();
					String accessToken = ctx.accessToken();

					Mono<List<Candle>> chain = Mono.just(List.<Candle>of());
					if (needIntraday) {
						String exchangeCode = MstockApiClient.intradayExchangeCode(prefix);
						chain = fetchIntraday(instrumentKey, apiKey, accessToken, exchangeCode, finalToken,
								typeAInterval, typeBInterval);
					}
					Mono<List<Candle>> fetch = chain.flatMap(intra -> {
						if (!intra.isEmpty() && !needHistorical) {
							return Mono.just(filterRange(intra, clampedFrom, clampedTo));
						}
						Mono<List<Candle>> hist = fetchHistoricalOnExchange(
								instrumentKey, apiKey, accessToken, primary, finalToken,
								typeAInterval, typeBInterval, fromA, toA, fromB, toB, intervalLabel)
								.flatMap(bars -> {
									if (!bars.isEmpty() || alternate == null || alternate.equals(primary)) {
										return Mono.just(bars);
									}
									log.debug("Candle empty on exchange={} for key={}, retrying {}",
											primary, instrumentKey, alternate);
									return fetchHistoricalOnExchange(
											instrumentKey, apiKey, accessToken, alternate, finalToken,
											typeAInterval, typeBInterval, fromA, toA, fromB, toB, intervalLabel);
								});
						if (intra.isEmpty()) {
							return hist.map(h -> filterRange(h, clampedFrom, clampedTo));
						}
						return hist.map(h -> filterRange(mergeCandles(intra, h), clampedFrom, clampedTo));
					});
					return brokerSessionService.translateAuthErrors(userId, ctx.broker(), fetch);
				});
	}

	private Mono<List<Candle>> fetchIntraday(
			String instrumentKey,
			String apiKey,
			String accessToken,
			String exchangeCode,
			String token,
			String typeAInterval,
			String typeBInterval) {
		log.debug("Candle MStock intraday key={} exchangeCode={} token={} intervalA={} intervalB={}",
				instrumentKey, exchangeCode, token, typeAInterval, typeBInterval);
		return mstockApiClient
				.getIntradayCandles(apiKey, accessToken, exchangeCode, token, typeAInterval, typeBInterval)
				.map(json -> logAndParse(instrumentKey, "intraday/" + exchangeCode, json))
				.onErrorResume(ex -> {
					log.warn("MStock intraday {} {} failed: {}", instrumentKey, exchangeCode, ex.toString());
					return Mono.just(List.of());
				});
	}

	private String resolveNumericToken(String instrumentKey) {
		String token = mstockInstrumentService.tokenForKey(instrumentKey);
		if (token == null || token.isBlank() || !token.chars().allMatch(Character::isDigit)) {
			String after = instrumentKey.contains(":")
					? instrumentKey.substring(instrumentKey.indexOf(':') + 1)
					: instrumentKey;
			if (after.chars().allMatch(Character::isDigit)) {
				token = after;
			}
			else {
				token = null;
			}
		}
		return token;
	}

	/** True when [from,to] overlaps today's IST cash session. */
	static boolean intersectsTodaySession(Instant from, Instant to) {
		LocalDate today = LocalDate.now(IST);
		Instant sessionStart = today.atTime(SESSION_OPEN).atZone(IST).toInstant();
		Instant sessionEnd = today.atTime(SESSION_CLOSE).atZone(IST).toInstant();
		Instant now = Instant.now();
		if (now.isBefore(sessionEnd)) {
			sessionEnd = now;
		}
		return from.isBefore(sessionEnd) && to.isAfter(sessionStart);
	}

	/** True when from is before today's session open (needs historical for prior day(s)). */
	static boolean spansPriorSessionDay(Instant from) {
		LocalDate today = LocalDate.now(IST);
		Instant todayOpen = today.atTime(SESSION_OPEN).atZone(IST).toInstant();
		return from.isBefore(todayOpen);
	}

	static List<Candle> mergeCandles(List<Candle> a, List<Candle> b) {
		Map<Instant, Candle> byTs = new java.util.TreeMap<>();
		if (a != null) {
			for (Candle c : a) {
				byTs.put(c.timestamp(), c);
			}
		}
		if (b != null) {
			for (Candle c : b) {
				byTs.put(c.timestamp(), c);
			}
		}
		return List.copyOf(byTs.values());
	}

	private Mono<List<Candle>> fetchHistoricalOnExchange(
			String instrumentKey,
			String apiKey,
			String accessToken,
			String exchange,
			String token,
			String typeAInterval,
			String typeBInterval,
			String fromA,
			String toA,
			String fromB,
			String toB,
			String intervalLabel) {
		log.debug("Candle MStock request key={} exchange={} token={} intervalA={} intervalB={} fromA={} toA={} fromB={} toB={}",
				instrumentKey, exchange, token, typeAInterval, typeBInterval, fromA, toA, fromB, toB);
		Mono<List<Candle>> typeBFetch = Mono.defer(() -> mstockApiClient
				.getHistoricalCandlesTypeB(apiKey, accessToken, exchange, token, typeBInterval, fromB, toB)
				.map(json -> logAndParse(instrumentKey, "B/" + exchange, json))
				.onErrorResume(ex -> {
					log.warn("MStock Type B historical {} {} {} failed: {}",
							instrumentKey, exchange, intervalLabel, ex.toString());
					return Mono.just(List.of());
				}));
		return mstockApiClient
				.getHistoricalCandlesTypeA(apiKey, accessToken, exchange, token, typeAInterval, fromA, toA)
				.flatMap(json -> {
					List<Candle> parsed = logAndParse(instrumentKey, "A/" + exchange, json);
					if (!parsed.isEmpty()) {
						log.debug("Candle path=A/{} produced bars={} key={}", exchange, parsed.size(), instrumentKey);
						return Mono.just(parsed);
					}
					log.debug("Candle Type A empty parse key={} exchange={}, trying Type B", instrumentKey, exchange);
					return typeBFetch.map(b -> {
						if (!b.isEmpty()) {
							log.debug("Candle path=B/{} produced bars={} key={}", exchange, b.size(), instrumentKey);
						}
						return b;
					});
				})
				.onErrorResume(ex -> {
					log.debug("Candle Type A failed key={} exchange={}, trying Type B: {}",
							instrumentKey, exchange, ex.toString());
					return typeBFetch;
				});
	}

	/**
	 * Preferred exchange for MStock historical — use FO segment for options (docs: NSE, NFO, BSE, BFO).
	 */
	static String historicalExchangePrimary(String instrumentKey) {
		String raw = keyExchangePrefix(instrumentKey);
		return raw.isBlank() ? "NSE" : raw;
	}

	/** Alternate segment when primary returns empty (NFO → try NSE for quotes-style tokens). */
	static String historicalExchangeAlternate(String instrumentKey) {
		String raw = keyExchangePrefix(instrumentKey);
		if ("NFO".equalsIgnoreCase(raw)) {
			return "NSE";
		}
		if ("BFO".equalsIgnoreCase(raw)) {
			return "BSE";
		}
		if ("NSE".equalsIgnoreCase(raw)) {
			return "NFO";
		}
		if ("BSE".equalsIgnoreCase(raw)) {
			return "BFO";
		}
		return null;
	}

	static String keyExchangePrefix(String instrumentKey) {
		if (instrumentKey == null || !instrumentKey.contains(":")) {
			return "NSE";
		}
		return instrumentKey.substring(0, instrumentKey.indexOf(':'));
	}

	private List<Candle> logAndParse(String instrumentKey, String path, JsonNode json) {
		List<Candle> parsed = parseCandles(json);
		log.debug("Candle MStock response key={} path={} parsed={} status={} message={}",
				instrumentKey,
				path,
				parsed.size(),
				json != null ? json.path("status").asString("") : "",
				json != null ? json.path("message").asString("") : "");
		if (parsed.isEmpty()) {
			log.warn("Historical parse empty for {} path={} status={} message={} raw={}",
					instrumentKey,
					path,
					json != null ? json.path("status").asString("") : "",
					json != null ? json.path("message").asString("") : "",
					truncateRaw(json));
		}
		return parsed;
	}

	/**
	 * Cap {@code to} at min(now, today 15:30 IST). Never leave it after session close.
	 */
	static Instant clampToSession(Instant to) {
		if (to == null) {
			to = Instant.now();
		}
		Instant now = Instant.now();
		Instant capped = to.isAfter(now) ? now : to;
		LocalDateTime ldt = LocalDateTime.ofInstant(capped, IST);
		LocalDateTime sessionEnd = ldt.toLocalDate().atTime(SESSION_CLOSE);
		if (ldt.isAfter(sessionEnd)) {
			return sessionEnd.atZone(IST).toInstant();
		}
		LocalDateTime sessionOpen = ldt.toLocalDate().atTime(SESSION_OPEN);
		if (ldt.isBefore(sessionOpen)) {
			// before open: use previous session close if we somehow got a pre-open "to"
			return sessionOpen.atZone(IST).toInstant();
		}
		return capped;
	}

	/** Ensure {@code from} is not after {@code to}; nudge to session open of {@code to}'s day if needed. */
	static Instant clampFromSession(Instant from, Instant to) {
		if (from == null) {
			return LocalDateTime.ofInstant(to, IST).toLocalDate().atTime(SESSION_OPEN).atZone(IST).toInstant();
		}
		if (!from.isBefore(to)) {
			return LocalDateTime.ofInstant(to, IST).toLocalDate().atTime(SESSION_OPEN).atZone(IST).toInstant();
		}
		return from;
	}

	static String truncateRaw(JsonNode json) {
		if (json == null) {
			return "";
		}
		String s = json.toString();
		if (s.length() <= 500) {
			return s;
		}
		return s.substring(0, 500) + "...";
	}

	static String formatMstockB(Instant instant) {
		return LocalDateTime.ofInstant(instant, IST).format(MSTOCK_DT_B);
	}

	static String formatMstockA(Instant instant) {
		return LocalDateTime.ofInstant(instant, IST).format(MSTOCK_DT_A);
	}

	static String toTypeAInterval(String typeBInterval) {
		if ("ONE_MINUTE".equalsIgnoreCase(typeBInterval)) {
			return "minute";
		}
		if ("FIVE_MINUTE".equalsIgnoreCase(typeBInterval)) {
			return "5minute";
		}
		if ("ONE_HOUR".equalsIgnoreCase(typeBInterval) || "THREE_HOUR".equalsIgnoreCase(typeBInterval)) {
			return "60minute";
		}
		return "60minute";
	}

	static String resolveIntervalLabel(String unit, String interval) {
		String iv = interval != null && !interval.isBlank() ? interval.trim() : "75";
		String u = unit != null && !unit.isBlank() ? unit.trim() : "minutes";
		if ("1".equals(iv) || "ONE_MINUTE".equalsIgnoreCase(iv)) {
			return "ONE_MINUTE";
		}
		if ("5".equals(iv) || "FIVE_MINUTE".equalsIgnoreCase(iv)) {
			return "FIVE_MINUTE";
		}
		if ("75".equals(iv) || "minutes".equalsIgnoreCase(u)) {
			return "ONE_HOUR";
		}
		return "ONE_HOUR";
	}

	/**
	 * Rough coverage check: at least one bar near start of session window and one near end,
	 * or enough bars for a trading morning.
	 */
	static boolean coverageOk(List<Candle> cached, Instant from, Instant to) {
		return coverageOk(cached, from, to, CandleCachePort.INTERVAL_1M);
	}

	static boolean coverageOk(List<Candle> cached, Instant from, Instant to, String redisInterval) {
		if (cached == null || cached.isEmpty()) {
			return false;
		}
		// Match MStock fetch clamp so after-hours requests (to=now) can complete at session close.
		Instant effectiveTo = clampToSession(to);
		Instant effectiveFrom = clampFromSession(from, effectiveTo);
		long fromSec = effectiveFrom.getEpochSecond();
		long toSec = effectiveTo.getEpochSecond();
		long bucketSec = CandleCachePort.bucketSeconds(redisInterval);
		long spanBuckets = Math.max(1, (toSec - fromSec) / bucketSec);
		double minBars = Math.min(spanBuckets * 0.5, redisInterval.equals(CandleCachePort.INTERVAL_5M) ? 12 : 30);
		if (cached.size() >= minBars) {
			long first = cached.get(0).timestamp().getEpochSecond();
			long last = cached.get(cached.size() - 1).timestamp().getEpochSecond();
			// within 15 minutes of requested window edges
			return first <= fromSec + 900 && last >= toSec - 900;
		}
		return false;
	}

	static List<Candle> filterRange(List<Candle> candles, Instant from, Instant to) {
		List<Candle> out = new ArrayList<>();
		for (Candle c : candles) {
			Instant ts = c.timestamp();
			if (!ts.isBefore(from) && !ts.isAfter(to)) {
				out.add(c);
			}
		}
		out.sort(Comparator.comparing(Candle::timestamp));
		return List.copyOf(out);
	}

	static List<Candle> parseCandles(JsonNode json) {
		if (json == null) {
			return List.of();
		}
		JsonNode candles = resolveCandleArray(json);
		if (candles == null || !candles.isArray()) {
			return List.of();
		}
		List<List<Object>> raw = new ArrayList<>();
		for (JsonNode row : candles) {
			if (!row.isArray() || row.size() < 5) {
				continue;
			}
			List<Object> cells = new ArrayList<>(row.size());
			for (JsonNode cell : row) {
				if (cell.isString()) {
					cells.add(cell.asString());
				}
				else if (cell.isNumber()) {
					cells.add(cell.asDouble());
				}
				else if (!cell.isMissingNode() && !cell.isNull() && cell.isValueNode()) {
					cells.add(cell.asString());
				}
				else {
					cells.add("");
				}
			}
			raw.add(cells);
		}
		return TradingViewIndicatorUtil.fromCandles(raw);
	}

	/** Prefer {@code data.candles}, then {@code data} array, then top-level {@code candles}. */
	static JsonNode resolveCandleArray(JsonNode json) {
		JsonNode candles = json.path("data").path("candles");
		if (candles.isArray()) {
			return candles;
		}
		JsonNode nested = json.path("data").path("data").path("candles");
		if (nested.isArray()) {
			return nested;
		}
		JsonNode data = json.path("data");
		if (data.isArray()) {
			return data;
		}
		JsonNode top = json.path("candles");
		if (top.isArray()) {
			return top;
		}
		return null;
	}
}

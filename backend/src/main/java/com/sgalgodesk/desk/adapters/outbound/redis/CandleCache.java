package com.sgalgodesk.desk.adapters.outbound.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.application.indicators.port.CandleCachePort;
import com.sgalgodesk.desk.domain.indicators.Candle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Redis HASH cache for OHLC bars. Key {@code candle:{interval}:{instrumentKey}}
 * (e.g. {@code candle:1m:…}, {@code candle:5m:…}), field = epoch seconds of bar open,
 * value = {@code o|h|l|c|v}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CandleCache implements CandleCachePort {

	private static final Duration TTL = Duration.ofDays(2);

	private final ReactiveStringRedisTemplate redis;

	public Mono<List<Candle>> getRange(String instrumentKey, Instant from, Instant to) {
		return getRange(instrumentKey, INTERVAL_1M, from, to);
	}

	@Override
	public Mono<List<Candle>> getRange(String instrumentKey, String interval, Instant from, Instant to) {
		String key = cacheKey(instrumentKey, interval);
		long min = from.getEpochSecond();
		long max = to.getEpochSecond();
		return redis.opsForHash()
				.entries(key)
				.collectList()
				.map(entries -> {
					List<Candle> out = new ArrayList<>();
					for (Map.Entry<Object, Object> e : entries) {
						try {
							long ts = Long.parseLong(String.valueOf(e.getKey()));
							if (ts < min || ts > max) {
								continue;
							}
							Candle c = decode(String.valueOf(e.getValue()), Instant.ofEpochSecond(ts));
							if (c != null) {
								out.add(c);
							}
						}
						catch (Exception ignored) {
							// skip bad field
						}
					}
					out.sort(Comparator.comparing(Candle::timestamp));
					return List.copyOf(out);
				})
				.onErrorResume(ex -> {
					log.warn("Redis candle get failed for {}: {}", instrumentKey, ex.toString());
					return Mono.just(List.of());
				});
	}

	public Mono<Void> upsert(String instrumentKey, List<Candle> candles) {
		return upsert(instrumentKey, INTERVAL_1M, candles);
	}

	@Override
	public Mono<Void> upsert(String instrumentKey, String interval, List<Candle> candles) {
		if (candles == null || candles.isEmpty()) {
			return Mono.empty();
		}
		String key = cacheKey(instrumentKey, interval);
		long bucketSec = bucketSeconds(interval);
		Map<String, String> fields = new HashMap<>();
		for (Candle c : candles) {
			long ts = c.timestamp().getEpochSecond();
			ts = (ts / bucketSec) * bucketSec;
			fields.put(Long.toString(ts), encode(c));
		}
		return redis.opsForHash()
				.putAll(key, fields)
				.doOnNext(ok -> log.debug("Redis candle upsert key={} fields={} ok={}", key, fields.size(), ok))
				.then(redis.expire(key, TTL))
				.then();
	}

	public static long bucketSeconds(String interval) {
		return CandleCachePort.bucketSeconds(interval);
	}

	static String cacheKey(String instrumentKey, String interval) {
		return "candle:" + interval + ":" + instrumentKey;
	}

	static String encode(Candle c) {
		return c.open() + "|" + c.high() + "|" + c.low() + "|" + c.close() + "|" + c.volume();
	}

	static Candle decode(String member, Instant ts) {
		String[] parts = member.split("\\|", -1);
		double o = Double.parseDouble(parts[0]);
		double h = parts.length > 1 ? Double.parseDouble(parts[1]) : o;
		double l = parts.length > 2 ? Double.parseDouble(parts[2]) : o;
		double cl = parts.length > 3 ? Double.parseDouble(parts[3]) : o;
		long v = parts.length > 4 ? (long) Double.parseDouble(parts[4]) : 0L;
		return new Candle(ts, o, h, l, cl, v, 0L);
	}
}

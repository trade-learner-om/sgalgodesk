package com.sgalgodesk.desk.application.indicators.port;

import java.time.Instant;
import java.util.List;

import com.sgalgodesk.desk.domain.indicators.Candle;

import reactor.core.publisher.Mono;

/** Outbound port: cache of OHLC bars per instrument and interval ("1m", "5m"). */
public interface CandleCachePort {

	String INTERVAL_1M = "1m";
	String INTERVAL_5M = "5m";

	/** Width in seconds of one bar bucket for the given interval label. */
	static long bucketSeconds(String interval) {
		return INTERVAL_5M.equals(interval) ? 300L : 60L;
	}

	Mono<List<Candle>> getRange(String instrumentKey, String interval, Instant from, Instant to);

	Mono<Void> upsert(String instrumentKey, String interval, List<Candle> candles);
}

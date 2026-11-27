package com.sgalgodesk.desk.application.market.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sgalgodesk.desk.domain.indicators.Candle;

import reactor.core.publisher.Mono;

/** Outbound port: market data from the active broker. */
public interface MarketDataPort {

	/** Last traded prices keyed by instrument key. */
	Mono<Map<String, Double>> ltp(UUID userId, List<String> instrumentKeys);

	Mono<OptionChain> optionChain(UUID userId, String instrumentKey, String expiryDate);

	/** Historical candles. {@code interval} e.g. "ONE_MINUTE", "FIVE_MINUTE". */
	Mono<List<Candle>> candles(UUID userId, String instrumentKey, String interval, Instant from, Instant to);

	record OptionChain(
			String underlyingKey,
			String underlyingSymbol,
			Double underlyingSpot,
			String expiryDate,
			Double pcr,
			Double maxPain,
			Integer lotSize,
			String futuresKey,
			String futuresSymbol,
			List<StrikeRow> strikes) {
	}

	record StrikeRow(double strike, OptionSide call, OptionSide put, Double pcr) {
	}

	record OptionSide(
			String instrumentKey,
			String tradingSymbol,
			Double ltp,
			Double closePrice,
			Double ltpChangePct,
			Double oi,
			Double prevOi,
			Double volume,
			Double iv,
			Double delta,
			Double theta,
			Double gamma,
			Double vega,
			Double pop,
			Double bid,
			Double ask,
			Integer lotSize) {
	}
}

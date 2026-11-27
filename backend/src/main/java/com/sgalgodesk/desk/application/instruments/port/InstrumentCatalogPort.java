package com.sgalgodesk.desk.application.instruments.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;

import reactor.core.publisher.Mono;

/** Outbound port: broker instrument master catalog. */
public interface InstrumentCatalogPort {

	/** Loads the catalog if not yet loaded; emits the number of instruments. */
	Mono<Integer> ensureLoaded(UUID userId);

	/** Free-text search; {@code asset} optionally narrows the asset class (nullable). */
	Mono<List<InstrumentHit>> search(String query, String asset);

	/** Emits the instrument, or completes empty when the key is unknown. */
	Mono<InstrumentRecord> findByKey(String instrumentKey);

	record InstrumentHit(
			String instrumentKey,
			String tradingSymbol,
			String name,
			String kind,
			Double strike,
			LocalDate expiry,
			Integer lotSize,
			Double tickSize,
			String underlyingKey,
			String underlyingSymbol,
			String assetClass) {
	}
}

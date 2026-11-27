package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.application.instruments.InstrumentSearchHit;
import com.sgalgodesk.desk.application.instruments.port.InstrumentCatalogPort;
import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@RequiredArgsConstructor
public class MstockInstrumentCatalogAdapter implements InstrumentCatalogPort {

	private final MstockInstrumentService instruments;

	@Override
	public Mono<Integer> ensureLoaded(UUID userId) {
		return instruments.ensureLoaded(userId);
	}

	@Override
	public Mono<List<InstrumentHit>> search(String query, String asset) {
		return Mono.fromCallable(() -> instruments.search(query, asset).stream()
						.map(MstockInstrumentCatalogAdapter::toHit)
						.toList())
				.subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<InstrumentRecord> findByKey(String instrumentKey) {
		return Mono.fromCallable(() -> instruments.findByKey(instrumentKey).orElse(null))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(rec -> rec == null ? Mono.empty() : Mono.just(rec));
	}

	private static InstrumentHit toHit(InstrumentSearchHit h) {
		return new InstrumentHit(
				h.instrumentKey(),
				h.tradingSymbol(),
				h.name(),
				h.kind(),
				h.strike(),
				h.expiry(),
				h.lotSize(),
				h.tickSize(),
				h.underlyingKey(),
				h.underlyingSymbol(),
				h.assetClass());
	}
}

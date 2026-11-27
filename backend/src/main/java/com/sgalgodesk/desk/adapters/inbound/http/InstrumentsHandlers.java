package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.ArrayList;
import java.util.List;

import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.instruments.InstrumentsDtos.TradableInstrument;
import com.sgalgodesk.desk.application.instruments.InstrumentsDtos.TradableInstrumentsResponse;
import com.sgalgodesk.desk.config.InstrumentsProperties;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockOptionChainService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class InstrumentsHandlers {

	private final MstockInstrumentService mstockInstrumentService;
	private final MstockOptionChainService mstockOptionChainService;
	private final BrokerSessionService brokerSessionService;
	private final InstrumentsProperties instrumentsProperties;

	public Mono<ServerResponse> search(ServerRequest request) {
		String query = requiredParam(request, "q");
		String asset = param(request, "asset", "all");
		return ready().map(n -> mstockInstrumentService.search(query, asset)).flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> underlyings(ServerRequest request) {
		String query = requiredParam(request, "q");
		String asset = param(request, "asset", "all");
		return ready().map(n -> mstockInstrumentService.searchUnderlyings(query, asset)).flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> expiries(ServerRequest request) {
		String underlyingKey = requiredParam(request, "underlyingKey");
		// NIFTY: expiries from Type A option-chain master (dctExp), not script-master CSV.
		if (mstockOptionChainService.isNiftyUnderlying(underlyingKey)) {
			return requireUserId().flatMap(mstockOptionChainService::listNiftyExpiries).flatMap(HttpSupport::ok);
		}
		return Mono.error(new ValidationFailed("Only NIFTY option chain expiries are supported for now"));
	}

	public Mono<ServerResponse> benchmarks(ServerRequest request) {
		return ready().map(n -> mstockInstrumentService.benchmarks()).flatMap(HttpSupport::ok);
	}

	/** Configured tradable indexes for algo Strategy Creator (no broker session required). */
	public Mono<ServerResponse> tradable(ServerRequest request) {
		List<TradableInstrument> list = new ArrayList<>();
		for (String symbol : instrumentsProperties.tradableSymbols()) {
			list.add(new TradableInstrument(
					symbol,
					instrumentsProperties.configuredLotSize(symbol),
					InstrumentsProperties.underlyingKeyForSymbol(symbol)));
		}
		return ok(new TradableInstrumentsResponse(list));
	}

	private Mono<Integer> ready() {
		return requireUserId()
				.flatMap(userId -> brokerSessionService.requireActiveSession(userId)
						.then(mstockInstrumentService.ensureLoaded(userId)));
	}
}

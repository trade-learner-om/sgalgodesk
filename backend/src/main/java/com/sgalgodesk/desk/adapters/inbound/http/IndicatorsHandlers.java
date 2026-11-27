package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.domain.indicators.Candle;
import com.sgalgodesk.desk.application.indicators.CandleService;
import com.sgalgodesk.desk.application.indicators.IndicatorDtos.CandleDto;
import com.sgalgodesk.desk.application.indicators.IndicatorDtos.CandlesResponse;
import com.sgalgodesk.desk.application.indicators.IndicatorSignalService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class IndicatorsHandlers {

	private final CandleService candleService;
	private final IndicatorSignalService indicatorSignalService;

	public Mono<ServerResponse> candles(ServerRequest request) {
		return requireUserId()
				.flatMap(userId -> Mono.defer(() -> candleService.fetchCandles(
						userId,
						requiredParam(request, "instrumentKey"),
						param(request, "unit", "minutes"),
						param(request, "interval", "75"),
						optionalParam(request, "from"),
						optionalParam(request, "to"))))
				.map(list -> new CandlesResponse(list.stream().map(IndicatorsHandlers::toDto).toList()))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> signal(ServerRequest request) {
		return requireUserId()
				.flatMap(userId -> Mono.defer(() -> indicatorSignalService.signal(userId, requiredParam(request, "instrumentKey"))))
				.flatMap(HttpSupport::ok);
	}

	private static CandleDto toDto(Candle c) {
		return new CandleDto(
				c.timestamp().toString(),
				c.open(),
				c.high(),
				c.low(),
				c.close(),
				c.volume(),
				c.openInterest());
	}
}

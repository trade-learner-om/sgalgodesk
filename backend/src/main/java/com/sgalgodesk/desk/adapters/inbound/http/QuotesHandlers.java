package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.quote.QuoteDtos.LtpRequest;
import com.sgalgodesk.desk.application.quote.QuoteService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class QuotesHandlers {

	private final QuoteService quoteService;
	private final Validator validator;

	public Mono<ServerResponse> ltp(ServerRequest request) {
		return validatedBody(request, LtpRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> quoteService.ltp(userId, body.instrumentKeys())))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> greeks(ServerRequest request) {
		return requireUserId()
				.flatMap(userId -> Mono.defer(() -> quoteService.greeks(userId, requiredParam(request, "instrumentKey"))))
				.flatMap(HttpSupport::ok);
	}
}

package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.strategy.StrategySuggestDtos.SuggestRequest;
import com.sgalgodesk.desk.application.strategy.StrategySuggestService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class StrategyHandlers {

	private final StrategySuggestService strategySuggestService;
	private final Validator validator;

	public Mono<ServerResponse> suggestCreditSpread(ServerRequest request) {
		return validatedBody(request, SuggestRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> strategySuggestService.suggest(userId, body)))
				.flatMap(HttpSupport::ok);
	}
}

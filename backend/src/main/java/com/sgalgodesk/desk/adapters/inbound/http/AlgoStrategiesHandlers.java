package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.RunStrategyRequest;
import com.sgalgodesk.desk.application.algo.AlgoStrategyDtos.SaveStrategyRequest;
import com.sgalgodesk.desk.application.algo.AlgoStrategyService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AlgoStrategiesHandlers {

	private final AlgoStrategyService algoStrategyService;
	private final Validator validator;

	public Mono<ServerResponse> list(ServerRequest request) {
		return requireUserId()
				.flatMap(algoStrategyService::listStrategies)
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> create(ServerRequest request) {
		return validatedBody(request, SaveStrategyRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> algoStrategyService.createStrategy(userId, body)))
				.flatMap(HttpSupport::created);
	}

	public Mono<ServerResponse> update(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> validatedBody(request, SaveStrategyRequest.class, validator)
						.flatMap(body -> requireUserId()
								.flatMap(userId -> algoStrategyService.updateStrategy(userId, id, body))))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> delete(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> algoStrategyService.deleteStrategy(userId, id)))
				.then(noContent());
	}

	public Mono<ServerResponse> run(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> validatedBody(request, RunStrategyRequest.class, validator)
						.flatMap(body -> requireUserId()
								.flatMap(userId -> algoStrategyService.runStrategy(userId, id, body))))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> stop(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> algoStrategyService.stopStrategy(userId, id)))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> lifecycle(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> algoStrategyService.getLifecycle(userId, id)))
				.flatMap(HttpSupport::ok);
	}
}

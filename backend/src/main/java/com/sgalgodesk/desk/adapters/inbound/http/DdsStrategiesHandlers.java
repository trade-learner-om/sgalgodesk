package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.dds.DdsDtos.RunDdsStrategyRequest;
import com.sgalgodesk.desk.application.dds.DdsDtos.SaveDdsStrategyRequest;
import com.sgalgodesk.desk.application.dds.DdsStrategyService;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class DdsStrategiesHandlers {

	private final DdsStrategyService ddsStrategyService;
	private final Validator validator;

	public Mono<ServerResponse> list(ServerRequest request) {
		return requireUserId()
				.flatMap(ddsStrategyService::listStrategies)
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> create(ServerRequest request) {
		return validatedBody(request, SaveDdsStrategyRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> ddsStrategyService.createStrategy(userId, body)))
				.flatMap(HttpSupport::created);
	}

	public Mono<ServerResponse> update(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> validatedBody(request, SaveDdsStrategyRequest.class, validator)
						.flatMap(body -> requireUserId()
								.flatMap(userId -> ddsStrategyService.updateStrategy(userId, id, body))))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> delete(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> ddsStrategyService.deleteStrategy(userId, id)))
				.then(noContent());
	}

	public Mono<ServerResponse> run(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> validatedBody(request, RunDdsStrategyRequest.class, validator)
						.flatMap(body -> requireUserId()
								.flatMap(userId -> ddsStrategyService.runStrategy(userId, id, body))))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> stop(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> ddsStrategyService.stopStrategy(userId, id)))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> lifecycle(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> ddsStrategyService.getLifecycle(userId, id)))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> history(ServerRequest request) {
		return Mono.fromCallable(() -> pathUuid(request, "id"))
				.flatMap(id -> requireUserId().flatMap(userId -> {
					LocalDate from = parseDate(request.queryParam("from").orElse(null));
					LocalDate to = parseDate(request.queryParam("to").orElse(null));
					String cursor = request.queryParam("cursor").orElse(null);
					int limit = request.queryParam("limit").map(s -> {
						try {
							return Integer.parseInt(s);
						}
						catch (NumberFormatException ex) {
							return 10;
						}
					}).orElse(10);
					return ddsStrategyService.getHistory(userId, id, from, to, cursor, limit);
				}))
				.flatMap(HttpSupport::ok);
	}

	private static LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (Exception ex) {
			throw new ValidationFailed("Invalid date; expected yyyy-MM-dd");
		}
	}
}

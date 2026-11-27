package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.orders.OrderDtos.ExitPositionsBody;
import com.sgalgodesk.desk.application.positions.PositionExitService;
import com.sgalgodesk.desk.application.positions.PositionsService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class PositionsHandlers {

	private final PositionsService positionsService;
	private final PositionExitService positionExitService;
	private final Validator validator;

	public Mono<ServerResponse> list(ServerRequest request) {
		return requireUserId().flatMap(positionsService::listPositions).flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> exit(ServerRequest request) {
		return validatedBody(request, ExitPositionsBody.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> positionExitService.exit(userId, body)))
				.flatMap(HttpSupport::ok);
	}
}

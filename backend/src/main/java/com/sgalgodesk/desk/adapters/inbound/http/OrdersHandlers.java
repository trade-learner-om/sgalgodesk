package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceMultiBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class OrdersHandlers {

	private final OrderService orderService;
	private final Validator validator;

	public Mono<ServerResponse> place(ServerRequest request) {
		return validatedBody(request, PlaceOrderBody.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> orderService.place(userId, body)))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> placeMulti(ServerRequest request) {
		return validatedBody(request, PlaceMultiBody.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> orderService.placeMulti(userId, body)))
				.flatMap(HttpSupport::ok);
	}
}

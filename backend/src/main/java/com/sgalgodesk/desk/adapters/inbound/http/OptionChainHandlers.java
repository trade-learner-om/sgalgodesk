package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.optionchain.OptionChainService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class OptionChainHandlers {

	private final OptionChainService optionChainService;

	public Mono<ServerResponse> chain(ServerRequest request) {
		return requireUserId()
				.flatMap(userId -> Mono.defer(() -> optionChainService.getChain(
						userId,
						requiredParam(request, "instrumentKey"),
						requiredParam(request, "expiryDate"))))
				.flatMap(HttpSupport::ok);
	}
}

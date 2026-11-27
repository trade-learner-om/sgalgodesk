package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.spread.SpreadHistoryService;
import com.sgalgodesk.desk.application.spread.SpreadHistoryService.SpreadHistoryRequest;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class SpreadHandlers {

	private final SpreadHistoryService spreadHistoryService;

	public Mono<ServerResponse> history(ServerRequest request) {
		return body(request, SpreadHistoryRequest.class)
				.flatMap(body -> requireUserId().flatMap(userId -> spreadHistoryService.history(userId, body)))
				.flatMap(HttpSupport::ok);
	}
}

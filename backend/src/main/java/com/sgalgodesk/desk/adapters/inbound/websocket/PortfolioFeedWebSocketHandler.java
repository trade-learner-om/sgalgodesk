package com.sgalgodesk.desk.adapters.inbound.websocket;

import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.Unauthorized;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;

import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.positions.PositionBookService;
import com.sgalgodesk.desk.application.positions.PositionBookService.UserBookHandle;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;
import com.sgalgodesk.desk.adapters.inbound.security.AppPrincipal;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Slf4j
public class PortfolioFeedWebSocketHandler implements WebSocketHandler {

	private final PositionBookService positionBookService;
	private final JsonMapper jsonMapper;

	@Override
	@NonNull
	public Mono<Void> handle(@org.jspecify.annotations.NonNull WebSocketSession session) {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.flatMap(PortfolioFeedWebSocketHandler::resolveUserId)
				.flatMap(userId -> positionBookService.acquire(userId)
						.flatMap(handle -> bridge(session, handle)
								.doFinally(_ -> positionBookService.release(handle))))
				.onErrorResume(ex -> {
					if (isClientAbort(ex)) {
						log.debug("Portfolio WS client already closed during setup: {}", ex.toString());
						return Mono.empty();
					}
					if (isAuthFailure(ex)) {
						log.warn("Portfolio WS auth failed: {}", ex.toString());
						return session.close(new CloseStatus(4401, "broker_token_expired"));
					}
					log.warn("Portfolio WS handshake failed: {}", ex.toString());
					return session.close();
				});
	}

	private Mono<Void> bridge(WebSocketSession session, UserBookHandle handle) {
		Mono<Void> inbound = session.receive().then();
		var outbound = handle.snapshots()
				.map(snapshot -> session.textMessage(toSnapshotJson(snapshot)));
		return session.send(outbound).and(inbound);
	}

	private String toSnapshotJson(PositionsResponse snapshot) {
		try {
			ObjectNode root = jsonMapper.valueToTree(snapshot);
			root.put("type", "snapshot");
			return jsonMapper.writeValueAsString(root);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize portfolio snapshot", ex);
		}
	}

	private static Mono<UUID> resolveUserId(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AppPrincipal principal)) {
			return Mono.error(new Unauthorized("unauthorized"));
		}
		return Mono.just(principal.getUserId());
	}

	private static boolean isClientAbort(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof AbortedException) {
				return true;
			}
			String msg = cur.getMessage();
			if (msg != null && msg.contains("Connection has been closed BEFORE send")) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}

	private static boolean isAuthFailure(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof BrokerAuthExpired || cur instanceof Conflict || cur instanceof Unauthorized) {
				return true;
			}
			if (cur instanceof ResponseStatusException rse) {
				String reason = rse.getReason();
				if (reason != null && (reason.contains("broker_token_expired") || reason.contains("broker_required"))) {
					return true;
				}
				if (rse.getStatusCode().value() == 401 || rse.getStatusCode().value() == 409) {
					return true;
				}
			}
			cur = cur.getCause();
		}
		return false;
	}
}

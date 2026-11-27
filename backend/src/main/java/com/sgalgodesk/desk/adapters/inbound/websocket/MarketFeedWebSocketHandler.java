package com.sgalgodesk.desk.adapters.inbound.websocket;

import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.Unauthorized;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketFeedHub;
import com.sgalgodesk.desk.adapters.inbound.security.AppPrincipal;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.channel.AbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketFeedWebSocketHandler implements WebSocketHandler {

	private final BrokerAccess brokerAccess;
	private final MstockInstrumentService mstockInstrumentService;
	private final MstockMarketFeedHub mstockMarketFeedHub;
	private final JsonMapper jsonMapper;

	@Override
	@NonNull
	public Mono<Void> handle(@org.jspecify.annotations.NonNull WebSocketSession session) {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.flatMap(MarketFeedWebSocketHandler::resolveUserId)
				.flatMap(userId -> brokerAccess.requireActive(userId)
						.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId)
								.then(Mono.defer(() -> bridgeMstock(session, userId, ctx)))))
				.onErrorResume(ex -> {
					if (isClientAbort(ex)) {
						log.debug("Market WS client already closed during setup: {}", ex.toString());
						return Mono.empty();
					}
					boolean authFailure = isAuthFailure(ex);
					if (authFailure) {
						log.warn("Market WS auth failed: {}", ex.toString());
						return session.close(new CloseStatus(4401, "broker_token_expired"));
					}
					log.warn("Market WS handshake failed: {}", ex.toString());
					return session.close();
				});
	}

	private Mono<Void> bridgeMstock(
			WebSocketSession session,
			UUID userId,
			BrokerAccess.ActiveBrokerContext ctx) {
		if (!mstockInstrumentService.feedTokenIndex().isEmpty()) {
			return bridgeMstockReady(session, userId, ctx);
		}
		log.warn("MStock market WS: instrument feed map empty; retrying ensureLoaded");
		return mstockInstrumentService.ensureLoaded(userId)
				.then(Mono.defer(() -> {
					if (mstockInstrumentService.feedTokenIndex().isEmpty()) {
						log.warn("MStock market WS: instrument feed map still empty after retry");
						return session.close(new CloseStatus(1011, "instruments_not_loaded"));
					}
					return bridgeMstockReady(session, userId, ctx);
				}));
	}

	private Mono<Void> bridgeMstockReady(
			WebSocketSession session,
			UUID userId,
			BrokerAccess.ActiveBrokerContext ctx) {
		MstockMarketFeedHub.ClientHandle handle = mstockMarketFeedHub.acquire(userId, ctx);
		Set<String> desired = ConcurrentHashMap.newKeySet();
		Sinks.Many<LtpTick> snapshotSink = Sinks.many().unicast().onBackpressureBuffer();

		Mono<Void> inbound = session.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.concatMap(text -> {
					try {
						boolean changed = applyClientMessage(text, desired, handle::setSubscriptions);
						if (!changed || desired.isEmpty()) {
							return Mono.empty();
						}
						// Seed missing LTPs (header indices) then replay so clients paint without waiting
						return handle.seedMissingIfSilent(desired)
								.onErrorResume(ex -> {
									log.debug("Market WS silent-seed skipped: {}", ex.toString());
									return Mono.empty();
								})
								.then(Mono.fromRunnable(() -> {
									for (LtpTick tick : handle.snapshotTicks(desired)) {
										snapshotSink.tryEmitNext(tick);
									}
								}));
					}
					catch (Exception ex) {
						log.debug("Invalid market WS client message: {}", ex.getMessage());
						return Mono.empty();
					}
				})
				.then()
				.doFinally(signal -> snapshotSink.tryEmitComplete());

		Flux<WebSocketMessage> outbound = Flux.merge(
						snapshotSink.asFlux(),
						handle.ticks().filter(tick -> desired.contains(tick.instrumentKey())))
				.map(tick -> toMessage(session, tick));

		Mono<Void> bridge = session.send(outbound).and(inbound);
		Mono<Void> authClose = handle.authExpired()
				.next()
				.flatMap(ignored -> {
					log.warn("Market WS closing with 4401 after hub auth expiry for user {}", userId);
					return session.close(new CloseStatus(4401, "broker_token_expired"));
				});

		return Mono.firstWithSignal(bridge, authClose)
				.doFinally(signal -> handle.release());
	}

	private WebSocketMessage toMessage(WebSocketSession session, LtpTick tick) {
		StringBuilder json = new StringBuilder(96);
		json.append("{\"instrumentKey\":\"").append(escape(tick.instrumentKey()))
				.append("\",\"ltp\":").append(tick.ltp());
		if (tick.close() != null) {
			json.append(",\"close\":").append(tick.close());
		}
		json.append('}');
		return session.textMessage(json.toString());
	}

	/** @return true when desired set was mutated (set/subscribe/unsubscribe). */
	private boolean applyClientMessage(
			String text,
			Set<String> desired,
			java.util.function.Consumer<Set<String>> sync) throws Exception {
		JsonNode root = jsonMapper.readTree(text);
		String action = textOrEmpty(root, "action").toLowerCase(Locale.ROOT);
		List<String> keys = readKeys(root.path("instrumentKeys"));
		if ("subscribe".equals(action)) {
			desired.addAll(keys);
		}
		else if ("unsubscribe".equals(action)) {
			keys.forEach(desired::remove);
		}
		else if ("set".equals(action)) {
			desired.clear();
			desired.addAll(keys);
		}
		else {
			return false;
		}
		sync.accept(Set.copyOf(desired));
		return true;
	}

	private static List<String> readKeys(JsonNode node) {
		List<String> keys = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode item : node) {
				if (item != null && item.isValueNode()) {
					String value = item.asString();
					if (value != null && !value.isBlank()) {
						keys.add(value.trim());
					}
				}
			}
		}
		return keys;
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
				if (reason != null && (reason.contains("token_expired") || reason.contains("required"))) {
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

	private static String textOrEmpty(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asString();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}

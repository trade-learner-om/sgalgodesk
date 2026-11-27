package com.sgalgodesk.desk.adapters.inbound.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketFeedHub;
import com.sgalgodesk.desk.adapters.inbound.security.AppPrincipal;
import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.Unauthorized;
import com.sgalgodesk.desk.domain.spread.SpreadCalculator;
import com.sgalgodesk.desk.domain.spread.SpreadLeg;
import com.sgalgodesk.desk.domain.spread.SpreadMetrics;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.channel.AbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dedicated WS for Running Positions spread P&amp;L — not multiplexed with /api/ws/market.
 * Live marks come from {@link MstockMarketFeedHub} LTPs.
 * Series values are 1-lot; {@code runningPnl} is full-qty P&amp;L for the label.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpreadFeedWebSocketHandler implements WebSocketHandler {

	private static final long MIN_EMIT_INTERVAL_MS = 750L;

	private final BrokerAccess brokerAccess;
	private final MstockInstrumentService mstockInstrumentService;
	private final MstockMarketFeedHub mstockMarketFeedHub;
	private final JsonMapper jsonMapper;

	@Override
	@NonNull
	public Mono<Void> handle(@org.jspecify.annotations.NonNull WebSocketSession session) {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.flatMap(SpreadFeedWebSocketHandler::resolveUserId)
				.flatMap(userId -> brokerAccess.requireActive(userId)
						.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId)
								.then(Mono.defer(() -> bridge(session, userId, ctx)))))
				.onErrorResume(ex -> {
					if (isClientAbort(ex)) {
						log.debug("Spread WS client already closed during setup: {}", ex.toString());
						return Mono.empty();
					}
					if (isAuthFailure(ex)) {
						log.warn("Spread WS auth failed: {}", ex.toString());
						return session.close(new CloseStatus(4401, "broker_token_expired"));
					}
					log.warn("Spread WS handshake failed: {}", ex.toString());
					return session.close();
				});
	}

	private Mono<Void> bridge(
			WebSocketSession session,
			UUID userId,
			BrokerAccess.ActiveBrokerContext ctx) {
		MstockMarketFeedHub.ClientHandle handle = mstockMarketFeedHub.acquire(userId, ctx);
		AtomicReference<List<SpreadLeg>> legsRef = new AtomicReference<>(List.of());
		Map<String, Double> lastLtp = new ConcurrentHashMap<>();
		AtomicLong lastEmitMs = new AtomicLong(0);
		Sinks.Many<String> forceEmit = Sinks.many().unicast().onBackpressureBuffer();

		Mono<Void> inbound = session.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.doOnNext(text -> {
					try {
						List<SpreadLeg> legs = parseSet(text);
						legsRef.set(legs);
						Set<String> keys = ConcurrentHashMap.newKeySet();
						for (SpreadLeg leg : legs) {
							keys.add(leg.instrumentKey());
						}
						handle.setSubscriptions(keys);
						// Seed from hub snapshot so chart can paint without waiting for a new tick
						Map<String, Double> snap = handle.snapshot(List.copyOf(keys));
						for (Map.Entry<String, Double> e : snap.entrySet()) {
							if (e.getValue() != null && e.getValue() > 0) {
								lastLtp.put(e.getKey(), e.getValue());
							}
						}
						String forced = buildPayload(legs, lastLtp, lastEmitMs, true);
						if (forced != null) {
							forceEmit.tryEmitNext(forced);
						}
					}
					catch (Exception ex) {
						log.debug("Invalid spread WS message: {}", ex.getMessage());
					}
				})
				.then();

		Flux<WebSocketMessage> outbound = Flux.merge(
				forceEmit.asFlux().map(session::textMessage),
				handle.ticks()
						.concatMap(tick -> {
							if (tick.ltp() > 0) {
								lastLtp.put(tick.instrumentKey(), tick.ltp());
							}
							String json = buildPayload(legsRef.get(), lastLtp, lastEmitMs, false);
							if (json == null) {
								return Mono.empty();
							}
							return Mono.just(session.textMessage(json));
						}));

		Mono<Void> bridge = session.send(outbound).and(inbound);
		Mono<Void> authClose = handle.authExpired()
				.next()
				.flatMap(ignored -> session.close(new CloseStatus(4401, "broker_token_expired")));

		return Mono.firstWithSignal(bridge, authClose)
				.onErrorResume(ex -> {
					if (isClientAbort(ex)) {
						log.debug("Spread WS client abort: {}", ex.toString());
						return Mono.empty();
					}
					return Mono.error(ex);
				})
				.doFinally(signal -> handle.release());
	}

	static String buildPayload(
			List<SpreadLeg> legs,
			Map<String, Double> lastLtp,
			AtomicLong lastEmitMs,
			boolean force) {
		if (legs == null || legs.isEmpty()) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastEmitMs.get() < MIN_EMIT_INTERVAL_MS) {
			return null;
		}
		SpreadMetrics m = SpreadCalculator.computeMetrics(legs, lastLtp);
		if (m == null) {
			return null;
		}
		lastEmitMs.set(now);
		return "{\"ts\":" + now
				+ ",\"spreadMark\":" + m.spreadMark1Lot()
				+ ",\"runningPnl\":" + m.runningPnlFull()
				+ ",\"runningPnl1Lot\":" + m.runningPnl1Lot()
				+ ",\"combinedPremium\":" + m.combinedPremium1Lot()
				+ ",\"lots\":" + m.lots()
				+ ",\"kind\":\"" + m.kind() + "\"}";
	}

	private List<SpreadLeg> parseSet(String text) throws Exception {
		JsonNode root = jsonMapper.readTree(text);
		String action = textOrEmpty(root, "action").toLowerCase(Locale.ROOT);
		if (!"set".equals(action)) {
			return List.of();
		}
		JsonNode legsNode = root.path("legs");
		if (!legsNode.isArray()) {
			return List.of();
		}
		List<SpreadLeg> legs = new ArrayList<>();
		for (JsonNode n : legsNode) {
			String key = textOrEmpty(n, "instrumentKey");
			if (key.isBlank()) {
				continue;
			}
			String sideRaw = textOrEmpty(n, "side").toUpperCase(Locale.ROOT);
			int side = "SELL".equals(sideRaw) || "S".equals(sideRaw) ? -1 : 1;
			int qty = (int) Math.round(n.path("quantity").asDouble(0));
			if (qty == 0) {
				qty = (int) Math.round(n.path("qty").asDouble(0));
			}
			int lotSize = (int) Math.round(n.path("lotSize").asDouble(0));
			int lots = (int) Math.round(n.path("lots").asDouble(0));
			double entry = n.path("entryPrice").asDouble(0);
			if (qty == 0 && lotSize == 0) {
				continue;
			}
			if (lotSize <= 0) {
				lotSize = lots > 0 && qty > 0 ? Math.max(1, qty / lots) : Math.max(1, Math.abs(qty));
			}
			if (qty == 0) {
				qty = lotSize * Math.max(1, lots);
			}
			if (lots <= 0) {
				lots = Math.max(1, Math.abs(qty) / Math.max(1, lotSize));
			}
			legs.add(new SpreadLeg(key, side, Math.abs(qty), lotSize, lots, entry));
		}
		return List.copyOf(legs);
	}

	private static String textOrEmpty(JsonNode node, String field) {
		JsonNode v = node.get(field);
		if (v == null || v.isNull() || !v.isValueNode()) {
			return "";
		}
		return v.asString("").trim();
	}

	private static Mono<UUID> resolveUserId(Authentication auth) {
		if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
			return Mono.error(new IllegalStateException("unauthenticated"));
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
			String msg = cur.getMessage();
			if (msg != null && (msg.contains("mstock_token_expired") || msg.contains("token_expired"))) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}
}

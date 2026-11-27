package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.adapters.inbound.websocket.LtpTick;
import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.config.MstockProperties;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

/**
 * Live-price channel: one upstream MStock market WS per user.
 * Live LTP comes from Type A binary ticks; if the feed is healthy but silent,
 * a one-shot REST OHLC/LTP seed may fill {@code lastLtp} (not a poller).
 */
@Service
@Slf4j
public class MstockMarketFeedHub implements BrokerSessionService.ActiveBrokerListener {

	private static final Duration IDLE_TTL = Duration.ofSeconds(30);
	private static final Duration DEFAULT_AWAIT = Duration.ofSeconds(10);
	private static final long[] RECONNECT_BACKOFF_MS = { 1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L };
	private static final int HARD_BACKOFF_AFTER = 3;

	private final MstockProperties mstockProperties;
	private final MstockInstrumentService mstockInstrumentService;
	private final MstockApiClient mstockApiClient;
	private final BrokerSessionService brokerSessionService;
	private final BrokerAccess brokerAccess;
	private final HttpClient outboundHttpClient;

	public MstockMarketFeedHub(
			MstockProperties mstockProperties,
			MstockInstrumentService mstockInstrumentService,
			MstockApiClient mstockApiClient,
			BrokerSessionService brokerSessionService,
			BrokerAccess brokerAccess,
			@Qualifier("outboundHttpClient") HttpClient outboundHttpClient) {
		this.mstockProperties = mstockProperties;
		this.mstockInstrumentService = mstockInstrumentService;
		this.mstockApiClient = mstockApiClient;
		this.brokerSessionService = brokerSessionService;
		this.brokerAccess = brokerAccess;
		this.outboundHttpClient = outboundHttpClient;
	}

	private final ConcurrentHashMap<UUID, UserHub> hubs = new ConcurrentHashMap<>();
	private Disposable idleSweeper;

	@PostConstruct
	void start() {
		brokerSessionService.addActiveBrokerListener(this);
		idleSweeper = Flux.interval(Duration.ofSeconds(10))
				.onBackpressureDrop()
				.subscribe(tick -> sweepIdle());
	}

	@PreDestroy
	void stop() {
		if (idleSweeper != null) {
			idleSweeper.dispose();
		}
		hubs.values().forEach(UserHub::shutdown);
		hubs.clear();
	}

	@Override
	public void onActiveBrokerChanged(UUID userId, BrokerId broker) {
		UserHub hub = hubs.remove(userId);
		if (hub != null) {
			hub.shutdown();
		}
	}

	/**
	 * Acquire a lease on the user's live-price hub (browser, algo, or quote).
	 * Hub stays open until all leases are released (and idle TTL elapses).
	 */
	public ClientHandle acquire(UUID userId, BrokerAccess.ActiveBrokerContext ctx) {
		UserHub hub = hubs.compute(userId, (id, existing) -> {
			if (existing != null && !existing.isShutdown()) {
				existing.touch();
				existing.updateCtx(ctx);
				return existing;
			}
			return new UserHub(userId, ctx);
		});
		return hub.registerClient();
	}

	/** Subscribe + await WS ticks for keys; releases the temporary lease afterward. */
	public Mono<Map<String, Double>> awaitLtps(UUID userId, List<String> instrumentKeys, Duration timeout) {
		List<String> keys = instrumentKeys == null ? List.of() : instrumentKeys.stream()
				.filter(k -> k != null && !k.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
		if (keys.isEmpty()) {
			return Mono.just(Map.of());
		}
		Duration wait = timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : DEFAULT_AWAIT;
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId)
						.then(Mono.using(
								() -> acquire(userId, ctx),
								handle -> {
									handle.setSubscriptions(Set.copyOf(keys));
									return handle.whenReady(wait)
											.then(Mono.defer(() -> awaitTicksOrSeed(handle, ctx, keys, wait)));
								},
								ClientHandle::release)));
	}

	private Mono<Map<String, Double>> awaitTicksOrSeed(
			ClientHandle handle,
			BrokerAccess.ActiveBrokerContext ctx,
			List<String> keys,
			Duration wait) {
		Map<String, Double> cached = handle.snapshot(keys);
		if (isComplete(cached, keys)) {
			return Mono.just(cached);
		}
		return handle.ticks()
				.filter(tick -> keys.contains(tick.instrumentKey()) && tick.ltp() > 0)
				.takeUntil(tick -> isComplete(handle.snapshot(keys), keys))
				.take(wait)
				.then(Mono.defer(() -> {
					Map<String, Double> after = handle.snapshot(keys);
					if (isComplete(after, keys)) {
						return Mono.just(after);
					}
					List<String> missing = keys.stream()
							.filter(k -> {
								Double v = after.get(k);
								return v == null || v <= 0;
							})
							.toList();
					return silentSeed(handle, ctx, missing)
							.then(Mono.fromCallable(() -> handle.snapshot(keys)));
				}));
	}

	private static boolean isComplete(Map<String, Double> prices, List<String> keys) {
		return keys.stream().allMatch(k -> {
			Double v = prices.get(k);
			return v != null && v > 0;
		});
	}

	/** One-shot REST OHLC→LTP into the hub when WS is healthy but silent. */
	private Mono<Void> silentSeed(
			ClientHandle handle,
			BrokerAccess.ActiveBrokerContext ctx,
			List<String> missingKeys) {
		if (missingKeys == null || missingKeys.isEmpty()) {
			return Mono.empty();
		}
		LinkedHashSet<String> requestKeys = new LinkedHashSet<>();
		for (String key : missingKeys) {
			requestKeys.addAll(mstockInstrumentService.quoteKeyCandidates(key));
		}
		if (requestKeys.isEmpty()) {
			return Mono.empty();
		}
		List<String> batch = new ArrayList<>(requestKeys);
		Set<String> subscribed = Set.copyOf(missingKeys);
		return mstockApiClient.getOhlc(ctx.apiKeyOrClientId(), ctx.accessToken(), batch)
				.doOnNext(json -> ingestRestQuotes(handle, json, subscribed))
				.onErrorResume(ex -> {
					if (isAuthFailure(ex)) {
						return Mono.error(ex);
					}
					log.debug("Silent-seed OHLC failed, trying LTP: {}", ex.toString());
					return mstockApiClient.getLtp(ctx.apiKeyOrClientId(), ctx.accessToken(), batch)
							.doOnNext(json -> ingestRestQuotes(handle, json, subscribed))
							.onErrorResume(ex2 -> {
								log.warn("Silent-seed REST quote failed: {}", ex2.toString());
								return Mono.empty();
							});
				})
				.then()
				.doOnSuccess(v -> log.info(
						"Silent-seed filled hub for user {} missingKeys={}",
						ctx.userId(), missingKeys.size()));
	}

	private void ingestRestQuotes(ClientHandle handle, JsonNode root, Set<String> subscribedKeys) {
		JsonNode data = root != null ? root.path("data") : null;
		if (data == null || !data.isObject()) {
			return;
		}
		int hit = 0;
		for (Map.Entry<String, JsonNode> entry : data.properties()) {
			JsonNode node = entry.getValue();
			if (node == null) {
				continue;
			}
			JsonNode lastPrice = node.path("last_price");
			if (!lastPrice.isNumber()) {
				lastPrice = node.path("ltp");
			}
			if (!lastPrice.isNumber() || lastPrice.asDouble() <= 0) {
				continue;
			}
			String responseKey = entry.getKey() == null ? "" : entry.getKey().replace("|", ":").trim();
			String canonical = mstockInstrumentService.canonicalKeyForQuote(responseKey);
			if (!subscribedKeys.contains(canonical)) {
				for (String sub : subscribedKeys) {
					boolean match = mstockInstrumentService.quoteKeyCandidates(sub).stream()
							.anyMatch(c -> c.equalsIgnoreCase(responseKey));
					if (match) {
						canonical = sub;
						break;
					}
				}
			}
			if (!subscribedKeys.contains(canonical)) {
				continue;
			}
			mstockInstrumentService.learnQuoteKey(canonical, responseKey);
			Double close = null;
			JsonNode closeNode = node.get("close");
			if (closeNode != null && closeNode.isNumber()) {
				close = closeNode.asDouble();
			}
			else {
				JsonNode ohlc = node.get("ohlc");
				if (ohlc != null) {
					JsonNode ohlcClose = ohlc.get("close");
					if (ohlcClose != null && ohlcClose.isNumber()) {
						close = ohlcClose.asDouble();
					}
				}
			}
			handle.ingestTick(new LtpTick(canonical, lastPrice.asDouble(), close));
			hit++;
		}
		log.info("Silent-seed ingested {} quotes for subscribed={}", hit, subscribedKeys.size());
	}

	private static boolean isAuthFailure(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof BrokerAuthExpired) {
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

	private void sweepIdle() {
		long now = System.currentTimeMillis();
		hubs.forEach((userId, hub) -> {
			if (hub.leaseCount() == 0 && now - hub.lastActivityMs() > IDLE_TTL.toMillis()) {
				if (hubs.remove(userId, hub)) {
					hub.shutdown();
					log.info("MStock market feed hub idle-closed for user {}", userId);
				}
			}
		});
	}

	public final class ClientHandle {
		private final UserHub hub;
		private final String clientId;
		private final AtomicBoolean released = new AtomicBoolean(false);

		private ClientHandle(UserHub hub, String clientId) {
			this.hub = hub;
			this.clientId = clientId;
		}

		public void setSubscriptions(Set<String> keys) {
			if (!released.get()) {
				hub.setClientSubscriptions(clientId, keys);
			}
		}

		/** Inject a tick into lastLtp + fanout (WS or silent-seed). */
		public void ingestTick(LtpTick tick) {
			if (!released.get()) {
				hub.onTick(tick);
			}
		}

		public Flux<LtpTick> ticks() {
			return hub.ticks();
		}

		/** Last-known LTPs for the given keys (WS ticks only; missing keys omitted). */
		public Map<String, Double> snapshot(List<String> keys) {
			return hub.snapshot(keys);
		}

		/** Full last-known ticks for replay to desk WS clients. */
		public List<LtpTick> snapshotTicks(java.util.Collection<String> keys) {
			return hub.snapshotTicks(keys);
		}

		/** Completes when upstream MStock WS is healthy (or already is). */
		public Mono<Void> whenReady(Duration timeout) {
			return hub.whenReady(timeout);
		}

		/**
		 * One-shot REST seed into hub for keys still missing after upstream is ready.
		 * Deduped per user so concurrent market WS clients share one REST call.
		 */
		public Mono<Void> seedMissingIfSilent(Collection<String> keys) {
			if (released.get()) {
				return Mono.empty();
			}
			return hub.seedMissingIfSilent(this, keys);
		}

		public boolean isHealthy() {
			return hub.isHealthy();
		}

		/** Emits once when broker auth is expired and browser clients should close with 4401. */
		public Flux<Boolean> authExpired() {
			return hub.authExpired();
		}

		public void release() {
			if (released.compareAndSet(false, true)) {
				hub.releaseClient(clientId);
			}
		}
	}

	private final class UserHub {
		private final UUID userId;
		private final AtomicReference<BrokerAccess.ActiveBrokerContext> ctxRef;
		private final ConcurrentHashMap<String, Set<String>> clientSubs = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<String, LtpTick> lastLtp = new ConcurrentHashMap<>();
		private final AtomicInteger clientSeq = new AtomicInteger();
		private final AtomicInteger leases = new AtomicInteger();
		private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
		private final AtomicBoolean shutdown = new AtomicBoolean(false);
		private final AtomicInteger reconnectAttempt = new AtomicInteger();
		private final Object connectionLock = new Object();
		private final Sinks.Many<Boolean> authExpiredSink =
				Sinks.many().multicast().onBackpressureBuffer(8, false);
		private final Sinks.Many<Boolean> readySink = Sinks.many().replay().latest();

		private volatile MstockMarketFeedConnection connection;
		private volatile Disposable tickSub;
		private volatile Disposable reconnectTimer;
		private final AtomicBoolean handshake502Warned = new AtomicBoolean(false);
		private final AtomicBoolean authExpired = new AtomicBoolean(false);
		private final AtomicBoolean upstreamReady = new AtomicBoolean(false);
		private final Sinks.Many<LtpTick> fanout = Sinks.many().multicast().onBackpressureBuffer(8192, false);
		/** Shared in-flight silent-seed Mono per instrument key (concurrent WS clients). */
		private final ConcurrentHashMap<String, Mono<Void>> seedInFlight = new ConcurrentHashMap<>();

		UserHub(UUID userId, BrokerAccess.ActiveBrokerContext ctx) {
			this.userId = userId;
			this.ctxRef = new AtomicReference<>(ctx);
			openConnection();
		}

		boolean isShutdown() {
			return shutdown.get();
		}

		int leaseCount() {
			return leases.get();
		}

		long lastActivityMs() {
			return lastActivityMs.get();
		}

		void touch() {
			lastActivityMs.set(System.currentTimeMillis());
		}

		void updateCtx(BrokerAccess.ActiveBrokerContext ctx) {
			if (ctx == null) {
				return;
			}
			BrokerAccess.ActiveBrokerContext prev = ctxRef.getAndSet(ctx);
			boolean tokenChanged = prev == null
					|| !Objects.equals(prev.accessToken(), ctx.accessToken())
					|| !Objects.equals(prev.apiKeyOrClientId(), ctx.apiKeyOrClientId());
			if (tokenChanged) {
				authExpired.set(false);
				reconnectAttempt.set(0);
				handshake502Warned.set(false);
				log.info("MStock feed hub refreshed credentials for user {}", userId);
				openConnection();
			}
		}

		ClientHandle registerClient() {
			touch();
			leases.incrementAndGet();
			String id = "c" + clientSeq.incrementAndGet();
			clientSubs.put(id, Set.of());
			return new ClientHandle(this, id);
		}

		void releaseClient(String clientId) {
			clientSubs.remove(clientId);
			leases.decrementAndGet();
			touch();
			resyncUpstream();
		}

		void setClientSubscriptions(String clientId, Set<String> keys) {
			touch();
			clientSubs.put(clientId, keys == null ? Set.of() : Set.copyOf(keys));
			resyncUpstream();
		}

		Flux<LtpTick> ticks() {
			return fanout.asFlux();
		}

		Map<String, Double> snapshot(List<String> keys) {
			Map<String, Double> out = new HashMap<>();
			if (keys == null) {
				return out;
			}
			for (String key : keys) {
				if (key == null || key.isBlank()) {
					continue;
				}
				LtpTick tick = lastLtp.get(key.trim());
				if (tick != null && tick.ltp() > 0) {
					out.put(key.trim(), tick.ltp());
				}
			}
			return out;
		}

		List<LtpTick> snapshotTicks(Collection<String> keys) {
			List<LtpTick> out = new ArrayList<>();
			if (keys == null) {
				return out;
			}
			for (String key : keys) {
				if (key == null || key.isBlank()) {
					continue;
				}
				LtpTick tick = lastLtp.get(key.trim());
				if (tick != null && tick.ltp() > 0) {
					out.add(tick);
				}
			}
			return out;
		}

		Mono<Void> seedMissingIfSilent(ClientHandle handle, Collection<String> keys) {
			if (shutdown.get() || handle == null || keys == null || keys.isEmpty()) {
				return Mono.empty();
			}
			List<String> requested = keys.stream()
					.filter(k -> k != null && !k.isBlank())
					.map(String::trim)
					.distinct()
					.toList();
			if (requested.isEmpty()) {
				return Mono.empty();
			}
			// Wait briefly for upstream; no-op if never ready (WS may still deliver later)
			return whenReady(Duration.ofSeconds(5))
					.then(Mono.defer(() -> seedMissingNow(handle, requested)));
		}

		private Mono<Void> seedMissingNow(ClientHandle handle, List<String> requested) {
			if (!upstreamReady.get()) {
				return Mono.empty();
			}
			List<String> missing = requested.stream()
					.filter(k -> {
						LtpTick t = lastLtp.get(k);
						return t == null || !(t.ltp() > 0);
					})
					.toList();
			if (missing.isEmpty()) {
				return Mono.empty();
			}

			List<Mono<Void>> waits = new ArrayList<>();
			synchronized (seedInFlight) {
				List<String> startBatch = new ArrayList<>();
				for (String key : missing) {
					Mono<Void> existing = seedInFlight.get(key);
					if (existing != null) {
						waits.add(existing);
					}
					else {
						startBatch.add(key);
					}
				}
				if (!startBatch.isEmpty()) {
					List<String> batchKeys = List.copyOf(startBatch);
					Mono<Void> batch = Mono.defer(() -> {
								BrokerAccess.ActiveBrokerContext ctx = ctxRef.get();
								if (ctx == null) {
									return Mono.empty();
								}
								List<String> stillMissing = batchKeys.stream()
										.filter(k -> {
											LtpTick t = lastLtp.get(k);
											return t == null || !(t.ltp() > 0);
										})
										.toList();
								if (stillMissing.isEmpty()) {
									return Mono.empty();
								}
								return silentSeed(handle, ctx, stillMissing);
							})
							.doFinally(sig -> {
								synchronized (seedInFlight) {
									for (String k : batchKeys) {
										seedInFlight.remove(k);
									}
								}
							})
							.cache();
					for (String k : batchKeys) {
						seedInFlight.put(k, batch);
					}
					waits.add(batch);
				}
			}
			if (waits.isEmpty()) {
				return Mono.empty();
			}
			return Mono.when(waits);
		}

		boolean isHealthy() {
			MstockMarketFeedConnection conn = connection;
			return upstreamReady.get() && conn != null && conn.isHealthy();
		}

		Mono<Void> whenReady(Duration timeout) {
			if (isHealthy()) {
				return Mono.empty();
			}
			Duration wait = timeout != null ? timeout : DEFAULT_AWAIT;
			return readySink.asFlux()
					.filter(Boolean::booleanValue)
					.next()
					.timeout(wait)
					.onErrorResume(ex -> {
						log.debug("Hub ready wait timed out for user {}: {}", userId, ex.toString());
						return Mono.empty();
					})
					.then();
		}

		Flux<Boolean> authExpired() {
			return authExpiredSink.asFlux();
		}

		void onTick(LtpTick tick) {
			if (tick == null || tick.instrumentKey() == null || tick.instrumentKey().isBlank()) {
				return;
			}
			// Only treat reconnect as recovered after a real tick (not mere WS upgrade).
			reconnectAttempt.set(0);
			handshake502Warned.set(false);
			lastLtp.put(tick.instrumentKey(), tick);
			fanout.tryEmitNext(tick);
		}

		private Set<String> unionKeys() {
			Set<String> all = new HashSet<>();
			for (Set<String> keys : clientSubs.values()) {
				all.addAll(keys);
			}
			return all;
		}

		private void resyncUpstream() {
			resyncUpstream(false);
		}

		private void resyncUpstream(boolean force) {
			MstockMarketFeedConnection conn = connection;
			if (conn == null) {
				return;
			}
			Set<String> keys = unionKeys();
			for (String key : keys) {
				MstockFeedToken meta = mstockInstrumentService.feedTokenForKey(key);
				if (meta == null && key != null) {
					String token = key.contains(":")
							? key.substring(key.indexOf(':') + 1).trim()
							: key.trim();
					if (!token.isEmpty() && token.chars().allMatch(Character::isDigit)) {
						meta = mstockInstrumentService.feedTokenForToken(token);
						if (meta == null) {
							String ensured = mstockInstrumentService.ensureFeedKeyForToken(
									key.contains(":") ? key.substring(0, key.indexOf(':')) : "NFO",
									token,
									null);
							if (ensured != null) {
								meta = mstockInstrumentService.feedTokenForKey(ensured);
								if (meta == null) {
									meta = mstockInstrumentService.feedTokenForToken(token);
								}
							}
						}
					}
				}
				if (meta != null) {
					conn.ensureFeedKey(key, meta);
				}
			}
			conn.syncSubscriptions(keys, force);
		}

		private Mono<BrokerAccess.ActiveBrokerContext> refreshCtx() {
			return brokerAccess.requireActive(userId)
					.doOnNext(ctx -> {
						ctxRef.set(ctx);
						authExpired.set(false);
					});
		}

		private void openConnection() {
			if (shutdown.get() || authExpired.get()) {
				return;
			}
			refreshCtx()
					.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId).thenReturn(ctx))
					.subscribe(
							this::openConnectionWithCtx,
							err -> {
								if (isAuthFailure(err)) {
									onAuthExpired(err);
								}
								else {
									log.warn("MStock feed hub could not refresh session/instruments for user {}: {}",
											userId, err.toString());
									scheduleReconnect();
								}
							});
		}

		private void openConnectionWithCtx(BrokerAccess.ActiveBrokerContext ctx) {
			if (shutdown.get() || authExpired.get()) {
				return;
			}
			synchronized (connectionLock) {
				if (shutdown.get() || authExpired.get()) {
					return;
				}
				closeConnectionOnly();
				upstreamReady.set(false);
				readySink.tryEmitNext(Boolean.FALSE);
				Map<String, MstockFeedToken> feedIndex = mstockInstrumentService.feedTokenIndex();
				if (feedIndex.isEmpty()) {
					log.warn("MStock feed hub: empty instrument index for user {} after ensureLoaded", userId);
					scheduleReconnect();
					return;
				}
				MstockMarketFeedConnection conn = new MstockMarketFeedConnection(
						outboundHttpClient,
						mstockProperties.wsBaseUrl(),
						ctx.apiKeyOrClientId(),
						ctx.accessToken(),
						feedIndex,
						mstockInstrumentService.feedKeyIndex());
				conn.setOnReady(() -> {
					upstreamReady.set(true);
					readySink.tryEmitNext(Boolean.TRUE);
					log.info("MStock upstream ready for user {}", userId);
					// Force subscribe+mode so broker pushes a cached quote tick
					resyncUpstream(true);
				});
				conn.setOnTerminal(error -> onUpstreamTerminal(conn, error));
				this.connection = conn;
				this.tickSub = conn.ticks()
						.publishOn(Schedulers.boundedElastic())
						.subscribe(
								this::onTick,
								err -> log.debug("Hub tick stream error: {}", err.toString()),
								() -> {
								});
				conn.start();
				resyncUpstream();
			}
		}

		private void onUpstreamTerminal(MstockMarketFeedConnection conn, Throwable error) {
			if (shutdown.get() || authExpired.get()) {
				return;
			}
			synchronized (connectionLock) {
				if (this.connection != conn) {
					return;
				}
				this.connection = null;
				upstreamReady.set(false);
				readySink.tryEmitNext(Boolean.FALSE);
				if (tickSub != null) {
					tickSub.dispose();
					tickSub = null;
				}
			}
			String err = error != null ? error.toString() : "clean";
			boolean gatewayOrDns = err.contains("502")
					|| err.contains("UnknownHostException")
					|| err.contains("Failed to resolve");
			if (gatewayOrDns) {
				if (handshake502Warned.compareAndSet(false, true)) {
					log.warn("MStock upstream unavailable for user {} ({}); waiting to reconnect (no REST fallback)",
							userId, err.contains("502") ? "502" : "DNS");
				}
				else {
					log.debug("MStock upstream still unavailable for user {}: {}", userId, err);
				}
			}
			else {
				log.warn("MStock upstream closed for user {}; reconnecting ({})", userId, err);
			}
			scheduleReconnect(gatewayOrDns);
		}

		private void scheduleReconnect() {
			scheduleReconnect(false);
		}

		private void scheduleReconnect(boolean gatewayOrDns) {
			if (shutdown.get() || authExpired.get() || leases.get() == 0) {
				return;
			}
			if (reconnectTimer != null && !reconnectTimer.isDisposed()) {
				return;
			}
			int attempt = reconnectAttempt.getAndIncrement();
			int idx = Math.min(attempt, RECONNECT_BACKOFF_MS.length - 1);
			if (gatewayOrDns && attempt >= HARD_BACKOFF_AFTER) {
				idx = RECONNECT_BACKOFF_MS.length - 1;
			}
			long delay = RECONNECT_BACKOFF_MS[idx];
			reconnectTimer = Mono.delay(Duration.ofMillis(delay))
					.subscribe(t -> {
						reconnectTimer = null;
						if (!shutdown.get() && !authExpired.get() && leases.get() > 0) {
							log.info("MStock feed reconnect attempt {} for user {}", attempt + 1, userId);
							openConnection();
						}
					});
		}

		private void onAuthExpired(Throwable err) {
			if (!authExpired.compareAndSet(false, true)) {
				return;
			}
			log.warn("MStock token expired for user {}; stopping feed and signaling clients ({})",
					userId, err != null ? err.toString() : "auth");
			synchronized (connectionLock) {
				closeConnectionOnly();
			}
			authExpiredSink.tryEmitNext(Boolean.TRUE);
		}

		private static boolean isAuthFailure(Throwable ex) {
			Throwable cur = ex;
			while (cur != null) {
				if (cur instanceof BrokerAuthExpired) {
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

		private void closeConnectionOnly() {
			if (reconnectTimer != null) {
				reconnectTimer.dispose();
				reconnectTimer = null;
			}
			if (tickSub != null) {
				tickSub.dispose();
				tickSub = null;
			}
			MstockMarketFeedConnection conn = connection;
			connection = null;
			if (conn != null) {
				conn.setOnTerminal(null);
				conn.stop();
			}
		}

		void shutdown() {
			if (!shutdown.compareAndSet(false, true)) {
				return;
			}
			closeConnectionOnly();
			fanout.tryEmitComplete();
			authExpiredSink.tryEmitComplete();
			clientSubs.clear();
			lastLtp.clear();
		}
	}
}

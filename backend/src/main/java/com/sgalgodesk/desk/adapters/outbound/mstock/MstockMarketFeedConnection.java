package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sgalgodesk.desk.adapters.inbound.websocket.LtpTick;

import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

/**
 * MStock Type A market data websocket → {@link LtpTick}.
 * Control messages (LOGIN/subscribe/mode) are text frames per the official SDK;
 * market data arrives as binary packets (sizes 8/28/32/44/48/184/200).
 * @see <a href="https://tradingapi.mstock.com/docs/v1/typeA/market-data/">Type A market data</a>
 */
public final class MstockMarketFeedConnection {

	private static final Logger log = LoggerFactory.getLogger(MstockMarketFeedConnection.class);
	private static final Duration PING_INTERVAL = Duration.ofSeconds(15);
	private static final int MAX_TEXT_DIAG_LOGS = 5;

	private final HttpClient httpClient;
	private final String wsBaseUrl;
	private final String apiKey;
	private final String accessToken;
	/** numeric token string → feed meta */
	private final Map<String, MstockFeedToken> byToken;
	/** instrumentKey → feed meta */
	private final Map<String, MstockFeedToken> byKey;
	private final Sinks.Many<LtpTick> ticks = Sinks.many().multicast().onBackpressureBuffer(4096, false);
	private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
	private final AtomicReference<Set<String>> subscribed = new AtomicReference<>(Set.of());
	private final AtomicReference<Set<String>> lastSent = new AtomicReference<>(Set.of());
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean loginSent = new AtomicBoolean(false);
	private final AtomicBoolean healthy = new AtomicBoolean(false);
	private final AtomicBoolean loggedFirstTick = new AtomicBoolean(false);
	private final AtomicLong tickCount = new AtomicLong();
	private final AtomicInteger textDiagLogged = new AtomicInteger();
	private volatile Disposable connectionDisposable;
	private volatile Consumer<Throwable> onTerminal;
	private volatile Runnable onReady;

	public MstockMarketFeedConnection(
			HttpClient httpClient,
			String wsBaseUrl,
			String apiKey,
			String accessToken,
			Map<String, MstockFeedToken> feedByToken) {
		this(httpClient, wsBaseUrl, apiKey, accessToken, feedByToken, null);
	}

	public MstockMarketFeedConnection(
			HttpClient httpClient,
			String wsBaseUrl,
			String apiKey,
			String accessToken,
			Map<String, MstockFeedToken> feedByToken,
			Map<String, MstockFeedToken> feedByKey) {
		this.httpClient = httpClient != null ? httpClient : HttpClient.create();
		this.wsBaseUrl = wsBaseUrl;
		this.apiKey = apiKey;
		this.accessToken = accessToken;
		this.byToken = new ConcurrentHashMap<>(feedByToken != null ? feedByToken : Map.of());
		Map<String, MstockFeedToken> keyMap = new HashMap<>();
		for (MstockFeedToken meta : this.byToken.values()) {
			if (meta != null && meta.instrumentKey() != null) {
				keyMap.put(meta.instrumentKey(), meta);
			}
		}
		if (feedByKey != null) {
			keyMap.putAll(feedByKey);
		}
		this.byKey = new ConcurrentHashMap<>(keyMap);
	}

	/** Register / alias a feed key so syncSubscriptions can subscribe it. */
	public void ensureFeedKey(String instrumentKey, MstockFeedToken meta) {
		if (meta == null) {
			return;
		}
		byToken.put(meta.token(), meta);
		if (meta.instrumentKey() != null && !meta.instrumentKey().isBlank()) {
			byKey.put(meta.instrumentKey(), meta);
		}
		if (instrumentKey != null && !instrumentKey.isBlank()) {
			byKey.put(instrumentKey.trim(), meta);
		}
	}

	public void setOnTerminal(Consumer<Throwable> onTerminal) {
		this.onTerminal = onTerminal;
	}

	public void setOnReady(Runnable onReady) {
		this.onReady = onReady;
	}

	public boolean isHealthy() {
		return healthy.get();
	}

	public Flux<LtpTick> ticks() {
		return ticks.asFlux();
	}

	public void emitSynthetic(LtpTick tick) {
		if (tick != null) {
			emitTick(tick);
		}
	}

	/** Package-visible: LOGIN/subscribe/mode must be text frames (official SDK). */
	static WebSocketFrame toOutboundFrame(String message) {
		return new TextWebSocketFrame(message);
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		String url = wsBaseUrl + "?ACCESS_TOKEN=" + accessToken + "&API_KEY=" + apiKey;
		log.debug("MStock market feed connecting: {}", safeWsHost(wsBaseUrl));
		URI uri = URI.create(url);
		connectionDisposable = httpClient
				.websocket()
				.uri(uri)
				.handle((inbound, outboundChannel) -> {
					outbound.tryEmitNext("LOGIN:" + accessToken);
					loginSent.set(true);
					healthy.set(true);
					Set<String> initial = subscribed.get();
					if (!initial.isEmpty()) {
						emitSubscribeAndMode(initial);
						lastSent.set(Set.copyOf(initial));
					}
					Runnable ready = onReady;
					if (ready != null) {
						ready.run();
					}
					Flux<Object> appFrames = outbound.asFlux().map(MstockMarketFeedConnection::toOutboundFrame);
					Flux<Object> pings = Flux.interval(PING_INTERVAL)
							.map(t -> new PingWebSocketFrame());
					Mono<Void> send = outboundChannel.sendObject(Flux.merge(appFrames, pings)).then();
					Mono<Void> receive = inbound.receiveFrames()
							.doOnNext(this::onWebSocketFrame)
							.then();
					Mono<Void> closeStatus = inbound.receiveCloseStatus()
							.doOnNext(status -> log.info("MStock market feed close status code={} reason={}",
									status.code(), status.reasonText() != null ? status.reasonText() : ""))
							.then();
					return Mono.when(send, receive, closeStatus);
				})
				.subscribe(
						unused -> {
						},
						error -> {
							healthy.set(false);
							log.warn("MStock market feed handshake/session failed: {}", error.toString());
							Consumer<Throwable> cb = onTerminal;
							if (cb != null) {
								cb.accept(error);
							}
							ticks.tryEmitComplete();
						},
						() -> {
							healthy.set(false);
							log.info("MStock market feed upstream completed (clean close)");
							Consumer<Throwable> cb = onTerminal;
							if (cb != null) {
								cb.accept(null);
							}
							ticks.tryEmitComplete();
						});
	}

	public synchronized void syncSubscriptions(Collection<String> desiredKeys) {
		syncSubscriptions(desiredKeys, false);
	}

	/**
	 * @param force when true (e.g. after upstream ready), re-send subscribe+mode for the full set
	 *              so the broker pushes a cached quote tick.
	 */
	public synchronized void syncSubscriptions(Collection<String> desiredKeys, boolean force) {
		Set<String> desired = new HashSet<>();
		List<String> dropped = new ArrayList<>();
		if (desiredKeys != null) {
			for (String key : desiredKeys) {
				if (key == null || key.isBlank()) {
					continue;
				}
				String trimmed = key.trim();
				if (byKey.containsKey(trimmed)) {
					desired.add(trimmed);
				}
				else {
					dropped.add(trimmed);
				}
			}
		}
		if (!dropped.isEmpty()) {
			log.warn("MStock feed subscribe dropped unresolved keys: {}", String.join(",", dropped));
		}
		subscribed.set(Set.copyOf(desired));
		if (!loginSent.get()) {
			return;
		}
		Set<String> current = lastSent.get();
		if (force && !desired.isEmpty()) {
			emitSubscribeAndMode(desired);
			lastSent.set(Set.copyOf(desired));
			logSubscribe(desired);
			return;
		}
		Set<String> toAdd = new HashSet<>(desired);
		toAdd.removeAll(current);
		Set<String> toRemove = new HashSet<>(current);
		toRemove.removeAll(desired);
		if (!toRemove.isEmpty()) {
			emitUnsubscribe(toRemove);
		}
		if (!toAdd.isEmpty()) {
			emitSubscribeAndMode(toAdd);
		}
		lastSent.set(Set.copyOf(desired));
		if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
			logSubscribe(desired);
		}
	}

	private void logSubscribe(Set<String> desired) {
		List<Integer> tokens = toTokenInts(desired);
		if (!desired.isEmpty() && tokens.isEmpty()) {
			log.warn("MStock feed subscribe: {} keys resolved but 0 numeric tokens", desired.size());
		}
		else if (!tokens.isEmpty()) {
			log.info("MStock feed subscribed tokens={} keys={}", tokens, desired.size());
		}
	}

	public Set<String> currentSubscriptions() {
		return subscribed.get();
	}

	public void stop() {
		healthy.set(false);
		started.set(false);
		loginSent.set(false);
		outbound.tryEmitComplete();
		Disposable d = connectionDisposable;
		if (d != null) {
			d.dispose();
		}
	}

	private void emitSubscribeAndMode(Set<String> keys) {
		List<Integer> tokens = toTokenInts(keys);
		if (tokens.isEmpty()) {
			return;
		}
		outbound.tryEmitNext(buildAction("subscribe", tokensArray(tokens)));
		outbound.tryEmitNext(buildModeQuote(tokens));
	}

	private void emitUnsubscribe(Set<String> keys) {
		List<Integer> tokens = toTokenInts(keys);
		if (tokens.isEmpty()) {
			return;
		}
		outbound.tryEmitNext(buildAction("unsubscribe", tokensArray(tokens)));
	}

	private List<Integer> toTokenInts(Set<String> keys) {
		List<Integer> tokens = new ArrayList<>();
		for (String key : keys) {
			MstockFeedToken meta = byKey.get(key);
			if (meta == null) {
				continue;
			}
			try {
				tokens.add(Integer.parseInt(meta.token().trim()));
			}
			catch (NumberFormatException ignored) {
				log.debug("Skip non-numeric MStock token for {}", key);
			}
		}
		return tokens;
	}

	private static String tokensArray(List<Integer> tokens) {
		StringBuilder v = new StringBuilder();
		boolean first = true;
		for (Integer t : tokens) {
			if (!first) {
				v.append(',');
			}
			first = false;
			v.append(t);
		}
		return v.toString();
	}

	private static String buildAction(String action, String tokensCsv) {
		return "{\"a\":\"" + action + "\",\"v\":[" + tokensCsv + "]}";
	}

	/** Official SDK: {"a":"mode","v":["quote", [tokens…]]} */
	private static String buildModeQuote(List<Integer> tokens) {
		return "{\"a\":\"mode\",\"v\":[\"quote\",[" + tokensArray(tokens) + "]]}";
	}

	private static String safeWsHost(String base) {
		if (base == null || base.isBlank()) {
			return "(missing)";
		}
		try {
			URI u = URI.create(base);
			String host = u.getHost();
			return host != null ? host : base;
		}
		catch (Exception ex) {
			return "(invalid)";
		}
	}

	private void onWebSocketFrame(WebSocketFrame frame) {
		// FluxReceive releases ReferenceCounted frames after onNext — do not release here.
		if (frame instanceof TextWebSocketFrame text) {
			String payload = text.text();
			if (textDiagLogged.getAndIncrement() < MAX_TEXT_DIAG_LOGS) {
				String preview = payload.length() > 120 ? payload.substring(0, 120) + "…" : payload;
				log.debug("MStock feed text frame: {}", preview);
			}
			return;
		}
		if (frame instanceof BinaryWebSocketFrame binary) {
			ByteBuffer nio = binary.content().nioBuffer();
			byte[] payload = new byte[nio.remaining()];
			nio.get(payload);
			onBinary(payload);
			return;
		}
		if (frame instanceof CloseWebSocketFrame close) {
			log.info("MStock market feed close frame code={} reason={}",
					close.statusCode(), close.reasonText() != null ? close.reasonText() : "");
		}
	}

	/** Package-visible for unit tests. */
	void onBinary(byte[] payload) {
		if (payload.length < 4) {
			return;
		}
		try {
			ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
			int packetCount = Short.toUnsignedInt(buf.getShort());
			for (int i = 0; i < packetCount && buf.remaining() >= 2; i++) {
				int packetSize = Short.toUnsignedInt(buf.getShort());
				if (packetSize <= 0 || buf.remaining() < packetSize) {
					break;
				}
				int start = buf.position();
				ByteBuffer packet = buf.slice(start, packetSize).order(ByteOrder.BIG_ENDIAN);
				parsePacket(packet, packetSize);
				buf.position(start + packetSize);
			}
		}
		catch (Exception ex) {
			log.debug("Skip MStock feed frame: {}", ex.getMessage());
		}
	}

	/**
	 * Packet sizes per official Type A SDK / docs:
	 * 8=ltp, 28=index quote, 32=index full, 44=equity quote, 48=index (docs),
	 * 184=full, 200=full+circuits.
	 */
	private void parsePacket(ByteBuffer buf, int packetSize) {
		if (packetSize < 8) {
			return;
		}
		int token = buf.getInt();
		int ltpRaw = buf.getInt();
		double divisor = 100.0;
		double ltp = ltpRaw / divisor;
		Double close = null;

		switch (packetSize) {
			case 8 -> {
				// LTP only
			}
			case 28, 48 -> {
				// Index quote/docs index: high, low, open, close at +8..+20
				if (buf.remaining() >= 16) {
					buf.position(buf.position() + 12); // skip high, low, open
					close = buf.getInt() / divisor;
				}
			}
			case 32 -> {
				// Index full: high, low, open, close at +8..+20
				if (buf.remaining() >= 16) {
					buf.position(buf.position() + 12);
					close = buf.getInt() / divisor;
				}
			}
			case 44, 184, 200 -> {
				// Equity quote/full: open at packet offset 28 → +20 after LTP
				if (buf.remaining() >= 32) {
					buf.position(buf.position() + 20); // skip qty/avg/vol/buy/sell
					buf.getInt(); // open
					buf.getInt(); // high
					buf.getInt(); // low
					close = buf.getInt() / divisor;
				}
			}
			default -> log.warn("MStock feed unknown packet length={} token={} (emitting LTP if present)",
					packetSize, token);
		}

		MstockFeedToken meta = byToken.get(String.valueOf(token));
		if (meta == null) {
			log.debug("MStock feed tick for unknown token={}", token);
			return;
		}
		if (!(ltp > 0) && close != null && close > 0) {
			// After hours: some feeds leave LTP 0 and only populate close
			ltp = close;
		}
		if (!(ltp > 0)) {
			return;
		}
		emitTick(new LtpTick(meta.instrumentKey(), ltp, close));
	}

	private void emitTick(LtpTick tick) {
		long n = tickCount.incrementAndGet();
		if (loggedFirstTick.compareAndSet(false, true)) {
			log.info("MStock feed first tick key={} ltp={} close={}",
					tick.instrumentKey(), tick.ltp(), tick.close());
		}
		else if (n % 500 == 0) {
			log.debug("MStock feed ticks so far={}", n);
		}
		ticks.tryEmitNext(tick);
	}
}

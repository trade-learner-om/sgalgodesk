package com.sgalgodesk.desk.application.quote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockApiClient;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockFeedToken;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketFeedHub;
import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.quote.QuoteDtos.GreeksResponse;
import com.sgalgodesk.desk.application.quote.QuoteDtos.LtpResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * LTP: prefer live hub snapshot (short wait), then fill misses via Type B / Type A REST.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuoteService {

	private static final Duration HUB_AWAIT = Duration.ofSeconds(3);

	private final MstockApiClient mstockApiClient;
	private final MstockInstrumentService mstockInstrumentService;
	private final MstockMarketFeedHub marketFeedHub;
	private final BrokerAccess brokerAccess;
	private final BrokerSessionService brokerSessionService;

	public Mono<LtpResponse> ltp(UUID userId, List<String> instrumentKeys) {
		List<String> keys = instrumentKeys == null ? List.of() : instrumentKeys.stream()
				.filter(k -> k != null && !k.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
		if (keys.isEmpty()) {
			return Mono.just(new LtpResponse(Map.of()));
		}
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> brokerSessionService.translateAuthErrors(
						userId,
						ctx.broker(),
						marketFeedHub.awaitLtps(userId, keys, HUB_AWAIT)
								.onErrorResume(ex -> {
									log.debug("Hub LTP await failed, using REST: {}", ex.toString());
									return Mono.just(Map.of());
								})
								.defaultIfEmpty(Map.of())
								.flatMap(fromHub -> {
									List<String> missing = keys.stream()
											.filter(k -> {
												Double v = fromHub.get(k);
												return v == null || v <= 0;
											})
											.toList();
									if (missing.isEmpty()) {
										return Mono.just(new LtpResponse(fromHub));
									}
									return mstockInstrumentService.ensureLoaded(userId)
											.then(Mono.defer(() -> fetchLtp(
													ctx.apiKeyOrClientId(), ctx.accessToken(), missing)))
											.map(rest -> {
												Map<String, Double> merged = new HashMap<>(fromHub);
												merged.putAll(rest.prices());
												return new LtpResponse(merged);
											});
								})));
	}

	public Mono<GreeksResponse> greeks(UUID userId, String instrumentKey) {
		return brokerAccess.requireActive(userId)
				.thenReturn(new GreeksResponse(instrumentKey, null, null, null, null, null));
	}

	private Mono<LtpResponse> fetchLtp(String apiKey, String accessToken, List<String> keys) {
		Map<String, List<String>> exchangeTokens = buildExchangeTokens(keys);
		Mono<Map<String, Double>> typeB = exchangeTokens.isEmpty()
				? Mono.just(Map.of())
				: mstockApiClient.getQuoteTypeB(apiKey, accessToken, "LTP", exchangeTokens)
						.map(json -> parseTypeBLtp(json, keys))
						.onErrorResume(ex -> {
							log.debug("Type B LTP failed, falling back to Type A: {}", ex.toString());
							return Mono.just(Map.of());
						});

		return typeB.flatMap(fromB -> {
			List<String> missing = keys.stream()
					.filter(k -> {
						Double v = fromB.get(k);
						return v == null || v <= 0;
					})
					.toList();
			if (missing.isEmpty()) {
				return Mono.just(new LtpResponse(fromB));
			}
			LinkedHashSet<String> requestKeys = new LinkedHashSet<>();
			for (String key : missing) {
				requestKeys.addAll(mstockInstrumentService.quoteKeyCandidates(key));
			}
			if (requestKeys.isEmpty()) {
				return Mono.just(new LtpResponse(fromB));
			}
			return mstockApiClient.getLtp(apiKey, accessToken, new ArrayList<>(requestKeys))
					.map(json -> {
						Map<String, Double> merged = new HashMap<>(fromB);
						merged.putAll(parseTypeALtp(json, missing));
						return new LtpResponse(merged);
					})
					.onErrorResume(ex -> {
						log.debug("Type A LTP failed: {}", ex.toString());
						return Mono.just(new LtpResponse(fromB));
					});
		});
	}

	private Map<String, List<String>> buildExchangeTokens(List<String> keys) {
		Map<String, LinkedHashSet<String>> byEx = new LinkedHashMap<>();
		for (String key : keys) {
			MstockFeedToken meta = mstockInstrumentService.feedTokenForKey(key);
			String token = meta != null ? meta.token() : tokenFromKey(key);
			if (token == null || token.isBlank() || !token.chars().allMatch(Character::isDigit)) {
				continue;
			}
			String exchange = quoteExchange(key, meta);
			byEx.computeIfAbsent(exchange, k -> new LinkedHashSet<>()).add(token);
		}
		Map<String, List<String>> out = new LinkedHashMap<>();
		byEx.forEach((ex, tokens) -> out.put(ex, List.copyOf(tokens)));
		return out;
	}

	private static String quoteExchange(String instrumentKey, MstockFeedToken meta) {
		if (instrumentKey != null) {
			if (instrumentKey.startsWith("BFO:") || instrumentKey.startsWith("BSE:")) {
				return instrumentKey.startsWith("BFO:") ? "BFO" : "BSE";
			}
			if (instrumentKey.startsWith("NFO:")) {
				return "NSE";
			}
		}
		if (meta != null) {
			return switch (meta.exchangeType()) {
				case 3 -> "BSE";
				case 4 -> "BFO";
				case 2 -> "NSE";
				default -> "NSE";
			};
		}
		return "NSE";
	}

	private static String tokenFromKey(String key) {
		if (key == null) {
			return null;
		}
		int colon = key.indexOf(':');
		return colon >= 0 ? key.substring(colon + 1) : key;
	}

	private Map<String, Double> parseTypeBLtp(JsonNode json, List<String> requestedKeys) {
		Map<String, Double> prices = new HashMap<>();
		Map<String, String> tokenToCanonical = new HashMap<>();
		for (String key : requestedKeys) {
			MstockFeedToken meta = mstockInstrumentService.feedTokenForKey(key);
			String token = meta != null ? meta.token() : tokenFromKey(key);
			if (token != null) {
				tokenToCanonical.put(token, key);
			}
		}
		JsonNode fetched = json.path("data").path("fetched");
		if (!fetched.isArray()) {
			return prices;
		}
		for (JsonNode row : fetched) {
			String token = text(row, "symbolToken", "symboltoken", "token");
			double ltp = number(row, "ltp", "last_price");
			if (token == null || ltp <= 0) {
				continue;
			}
			String canonical = tokenToCanonical.get(token);
			if (canonical == null) {
				MstockFeedToken meta = mstockInstrumentService.feedTokenForToken(token);
				canonical = meta != null ? meta.instrumentKey() : null;
			}
			if (canonical != null) {
				prices.put(canonical, ltp);
			}
		}
		return prices;
	}

	private Map<String, Double> parseTypeALtp(JsonNode json, List<String> requestedKeys) {
		Map<String, Double> prices = new HashMap<>();
		Map<String, String> responseToCanonical = new HashMap<>();
		for (String key : requestedKeys) {
			if (key == null || key.isBlank()) {
				continue;
			}
			String canonical = key.trim();
			for (String candidate : mstockInstrumentService.quoteKeyCandidates(canonical)) {
				responseToCanonical.put(normalizeKey(candidate).toLowerCase(Locale.ROOT), canonical);
			}
			responseToCanonical.put(normalizeKey(canonical).toLowerCase(Locale.ROOT), canonical);
		}
		JsonNode data = json.path("data");
		if (data.isObject()) {
			for (Map.Entry<String, JsonNode> entry : data.properties()) {
				JsonNode lastPrice = entry.getValue().path("last_price");
				if (!lastPrice.isNumber()) {
					lastPrice = entry.getValue().path("ltp");
				}
				if (!lastPrice.isNumber()) {
					continue;
				}
				String responseKey = normalizeKey(entry.getKey());
				String mapped = responseToCanonical.get(responseKey.toLowerCase(Locale.ROOT));
				if (mapped == null) {
					mapped = mstockInstrumentService.canonicalKeyForQuote(responseKey);
				}
				mstockInstrumentService.learnQuoteKey(mapped, responseKey);
				prices.put(mapped, lastPrice.asDouble());
				prices.putIfAbsent(responseKey, lastPrice.asDouble());
			}
		}
		return prices;
	}

	private static String normalizeKey(String key) {
		return key.replace("|", ":");
	}

	private static String text(JsonNode row, String... fields) {
		for (String field : fields) {
			JsonNode n = row.get(field);
			if (n != null && !n.isNull() && n.isValueNode()) {
				String v = n.asString();
				if (v != null && !v.isBlank()) {
					return v.trim();
				}
			}
		}
		return null;
	}

	private static double number(JsonNode row, String... fields) {
		for (String field : fields) {
			JsonNode n = row.get(field);
			if (n != null && n.isNumber()) {
				return n.asDouble();
			}
			if (n != null && n.isString()) {
				try {
					return Double.parseDouble(n.asString().trim());
				}
				catch (NumberFormatException ignored) {
					// try next
				}
			}
		}
		return 0;
	}
}

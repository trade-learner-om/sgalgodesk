package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;

import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.config.MstockProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class MstockApiClient {

	private final WebClient webClient;
	private final MstockProperties properties;
	private final JsonMapper jsonMapper;
	private final HttpClient jdkHttpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public MstockApiClient(
			@Qualifier("mstockWebClient") WebClient mstockWebClient,
			MstockProperties properties,
			JsonMapper jsonMapper) {
		this.webClient = mstockWebClient;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	public Mono<JsonNode> login(String username, String password) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", username);
		form.add("password", password);
		return webClient.post()
				.uri("/openapi/typea/connect/login")
				.header("X-Mirae-Version", "1")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock login failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> sessionToken(String apiKey, String otp) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("api_key", apiKey);
		form.add("request_token", otp);
		form.add("checksum", "L");
		return webClient.post()
				.uri("/openapi/typea/session/token")
				.header("X-Mirae-Version", "1")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock session token failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> verifyTotp(String apiKey, String totp) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("api_key", apiKey);
		form.add("totp", totp);
		return webClient.post()
				.uri("/openapi/typea/session/verifytotp")
				.header("X-Mirae-Version", "1")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock TOTP failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> getLtp(String apiKey, String accessToken, List<String> instruments) {
		return quoteGet("/openapi/typea/instruments/quote/ltp", apiKey, accessToken, instruments, "MStock LTP failed: ");
	}

	public Mono<JsonNode> getOhlc(String apiKey, String accessToken, List<String> instruments) {
		return quoteGet("/openapi/typea/instruments/quote/ohlc", apiKey, accessToken, instruments, "MStock OHLC failed: ");
	}

	private Mono<JsonNode> quoteGet(
			String path,
			String apiKey,
			String accessToken,
			List<String> instruments,
			String errorPrefix) {
		return webClient.get()
				.uri(uriBuilder -> {
					var b = uriBuilder.path(path);
					for (String i : instruments) {
						b.queryParam("i", i);
					}
					return b.build();
				})
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable(errorPrefix + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<String> getScriptMasterCsv(String apiKey, String accessToken) {
		return webClient.get()
				.uri("/openapi/typea/instruments/scriptmaster")
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock script master failed: " + body))))
				.bodyToMono(String.class);
	}

	public Mono<JsonNode> getOptionChainMaster(String apiKey, String accessToken, int exch) {
		return webClient.get()
				.uri("/openapi/typea/getoptionchainmaster/{exch}", exch)
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock option chain master failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> getOptionChain(String apiKey, String accessToken, int exch, long expiry, String token) {
		return webClient.get()
				.uri("/openapi/typea/GetOptionChain/{exch}/{expiry}/{token}", exch, expiry, token)
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock option chain failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> placeOrder(
			String apiKey,
			String accessToken,
			String variety,
			Map<String, String> fields) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		fields.forEach(form::add);
		return webClient.post()
				.uri("/openapi/typea/orders/{variety}", variety)
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock place order failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> getOrders(String apiKey, String accessToken) {
		return webClient.get()
				.uri("/openapi/typea/orders")
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock order book failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	public Mono<JsonNode> cancelOrder(String apiKey, String accessToken, String variety, String orderId) {
		return webClient.delete()
				.uri("/openapi/typea/orders/{variety}/{orderId}", variety, orderId)
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock cancel order failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	/**
	 * Trade history for the current IST calendar day. MStock Type A requires
	 * {@code fromdate}/{@code todate} ({@code yyyy-MM-dd}) as form-urlencoded GET body.
	 */
	public Mono<JsonNode> getTrades(String apiKey, String accessToken) {
		String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
		return getTrades(apiKey, accessToken, today, today);
	}

	/**
	 * Trade history for {@code [fromDate, toDate]} (inclusive, {@code yyyy-MM-dd}).
	 * Uses JDK HttpClient so the form body is actually sent (WebClient/Reactor Netty often drops GET bodies).
	 */
	public Mono<JsonNode> getTrades(String apiKey, String accessToken, String fromDate, String toDate) {
		return Mono.fromCallable(() -> {
					String from = fromDate != null && !fromDate.isBlank()
							? fromDate.trim()
							: LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
					String to = toDate != null && !toDate.isBlank()
							? toDate.trim()
							: from;
					String form = "fromdate=" + URLEncoder.encode(from, StandardCharsets.UTF_8)
							+ "&todate=" + URLEncoder.encode(to, StandardCharsets.UTF_8);
					String base = properties.apiBaseUrl();
					if (base.endsWith("/")) {
						base = base.substring(0, base.length() - 1);
					}
					HttpRequest request = HttpRequest.newBuilder()
							.uri(URI.create(base + "/openapi/typea/trades"))
							.timeout(Duration.ofSeconds(60))
							.method("GET", HttpRequest.BodyPublishers.ofString(form))
							.header("X-Mirae-Version", "1")
							.header("Authorization", "token " + apiKey + ":" + accessToken)
							.header("Content-Type", "application/x-www-form-urlencoded")
							.build();
					HttpResponse<String> response = jdkHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
					int code = response.statusCode();
					String respBody = response.body() != null ? response.body() : "";
					if (code == 401 || code == 403) {
						throw new BrokerAuthExpired("mstock_token_expired");
					}
					if (code >= 400) {
						throw new BrokerUnavailable("MStock trades failed: " + respBody);
					}
					return jsonMapper.readTree(respBody);
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<JsonNode> getPositionsTypeB(String apiKey, String accessToken) {
		return webClient.get()
				.uri("/openapi/typeb/portfolio/positions")
				.header("X-Mirae-Version", "1")
				.header("Authorization", "Bearer " + accessToken)
				.header("X-PrivateKey", apiKey)
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> Mono.error(new BrokerUnavailable("MStock positions failed: " + body))))
				.bodyToMono(JsonNode.class);
	}

	/**
	 * Type B LTP/OHLC by exchange → symbol tokens. MStock positions only expose symboltoken (no LTP).
	 */
	public Mono<JsonNode> getQuoteTypeB(
			String apiKey,
			String accessToken,
			String mode,
			Map<String, List<String>> exchangeTokens) {
		Map<String, Object> body = Map.of(
				"mode", mode == null || mode.isBlank() ? "LTP" : mode,
				"exchangeTokens", exchangeTokens);
		return webClient.method(org.springframework.http.HttpMethod.GET)
				.uri("/openapi/typeb/instruments/quote")
				.header("X-Mirae-Version", "1")
				.header("Authorization", "Bearer " + accessToken)
				.header("X-PrivateKey", apiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(bodyText -> Mono.error(new BrokerUnavailable("MStock Type B quote failed: " + bodyText))))
				.bodyToMono(JsonNode.class);
	}

	/**
	 * Type A historical candles — path + query params (no body). Prefer this over Type B
	 * because Reactor Netty / many clients strip GET bodies.
	 * Interval: {@code minute}, {@code 60minute}, etc. Dates: {@code yyyy-MM-dd HH:mm:ss}.
	 */
	public Mono<JsonNode> getHistoricalCandlesTypeA(
			String apiKey,
			String accessToken,
			String exchange,
			String symbolToken,
			String interval,
			String fromDateTime,
			String toDateTime) {
		return webClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/openapi/typea/instruments/historical/{exchange}/{token}/{interval}")
						.queryParam("from", fromDateTime)
						.queryParam("to", toDateTime)
						.build(exchange, symbolToken, interval))
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(bodyText -> Mono.error(new BrokerUnavailable("MStock Type A historical failed: " + bodyText))))
				.bodyToMono(JsonNode.class);
	}

	/**
	 * Type B historical candles — GET with JSON body (MStock docs). Uses JDK HttpClient
	 * so the body is actually sent (WebClient/Reactor Netty often drops GET bodies → 415).
	 * Interval: {@code ONE_MINUTE}. Dates: {@code yyyy-MM-dd HH:mm}.
	 */
	public Mono<JsonNode> getHistoricalCandlesTypeB(
			String apiKey,
			String accessToken,
			String exchange,
			String symbolToken,
			String interval,
			String fromDate,
			String toDate) {
		return Mono.fromCallable(() -> {
					Map<String, String> body = Map.of(
							"exchange", exchange,
							"symboltoken", symbolToken,
							"interval", interval,
							"fromdate", fromDate,
							"todate", toDate);
					String json = jsonMapper.writeValueAsString(body);
					String base = properties.apiBaseUrl();
					if (base.endsWith("/")) {
						base = base.substring(0, base.length() - 1);
					}
					HttpRequest request = HttpRequest.newBuilder()
							.uri(URI.create(base + "/openapi/typeb/instruments/historical"))
							.timeout(Duration.ofSeconds(60))
							.method("GET", HttpRequest.BodyPublishers.ofString(json))
							.header("X-Mirae-Version", "1")
							.header("Authorization", "Bearer " + accessToken)
							.header("X-PrivateKey", apiKey)
							.header("Content-Type", "application/json")
							.build();
					HttpResponse<String> response = jdkHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
					int code = response.statusCode();
					String respBody = response.body() != null ? response.body() : "";
					if (code == 401 || code == 403) {
						throw new BrokerAuthExpired("mstock_token_expired");
					}
					if (code >= 400) {
						throw new BrokerUnavailable("MStock Type B historical failed: " + respBody);
					}
					return jsonMapper.readTree(respBody);
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * Type A intraday candles for the current session only.
	 * Path: {@code /openapi/typea/instruments/intraday/{exchangeCode}/{token}/{interval}}
	 * Exchange codes: 1=NSE, 2=NFO, 4=BSE, 5=BFO. Interval: {@code minute}, {@code 5minute}, …
	 */
	public Mono<JsonNode> getIntradayCandlesTypeA(
			String apiKey,
			String accessToken,
			String exchangeCode,
			String symbolToken,
			String interval) {
		return webClient.get()
				.uri("/openapi/typea/instruments/intraday/{exchange}/{token}/{interval}",
						exchangeCode, symbolToken, interval)
				.headers(h -> authHeaders(h, apiKey, accessToken))
				.retrieve()
				.onStatus(this::isAuthFailure, response -> Mono.error(new BrokerAuthExpired("mstock_token_expired")))
				.onStatus(status -> status.value() >= 400, response -> response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(bodyText -> Mono.error(new BrokerUnavailable("MStock Type A intraday failed: " + bodyText))))
				.bodyToMono(JsonNode.class);
	}

	/**
	 * Type B intraday candles for the current session only (POST body).
	 * Exchange codes as strings: {@code "1"}=NSE, {@code "2"}=NFO, …
	 * Interval: {@code FIVE_MINUTE}, {@code ONE_MINUTE}, …
	 */
	public Mono<JsonNode> getIntradayCandlesTypeB(
			String apiKey,
			String accessToken,
			String exchangeCode,
			String symbolToken,
			String interval) {
		return Mono.fromCallable(() -> {
					Map<String, String> body = Map.of(
							"exchange", exchangeCode,
							"symboltoken", symbolToken,
							"interval", interval);
					String json = jsonMapper.writeValueAsString(body);
					String base = properties.apiBaseUrl();
					if (base.endsWith("/")) {
						base = base.substring(0, base.length() - 1);
					}
					HttpRequest request = HttpRequest.newBuilder()
							.uri(URI.create(base + "/openapi/typeb/instruments/intraday"))
							.timeout(Duration.ofSeconds(60))
							.POST(HttpRequest.BodyPublishers.ofString(json))
							.header("X-Mirae-Version", "1")
							.header("Authorization", "Bearer " + accessToken)
							.header("X-PrivateKey", apiKey)
							.header("Content-Type", "application/json")
							.build();
					HttpResponse<String> response = jdkHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
					int code = response.statusCode();
					String respBody = response.body() != null ? response.body() : "";
					if (code == 401 || code == 403) {
						throw new BrokerAuthExpired("mstock_token_expired");
					}
					if (code >= 400) {
						throw new BrokerUnavailable("MStock Type B intraday failed: " + respBody);
					}
					return jsonMapper.readTree(respBody);
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * Prefers Type A intraday; falls through to Type B when bars are empty or Type A errors.
	 */
	public Mono<JsonNode> getIntradayCandles(
			String apiKey,
			String accessToken,
			String exchangeCode,
			String symbolToken,
			String typeAInterval,
			String typeBInterval) {
		Mono<JsonNode> typeB = Mono.defer(() -> getIntradayCandlesTypeB(
						apiKey, accessToken, exchangeCode, symbolToken, typeBInterval)
				.doOnSubscribe(s -> log.debug(
						"Candle Type B intraday exchange={} token={} interval={}",
						exchangeCode, symbolToken, typeBInterval))
				.doOnSuccess(json -> log.debug(
						"Candle Type B intraday OK exchange={} token={} hasBars={}",
						exchangeCode, symbolToken, hasCandleBars(json))));

		return getIntradayCandlesTypeA(apiKey, accessToken, exchangeCode, symbolToken, typeAInterval)
				.doOnSubscribe(s -> log.debug(
						"Candle Type A intraday exchange={} token={} interval={}",
						exchangeCode, symbolToken, typeAInterval))
				.flatMap(json -> {
					boolean bars = hasCandleBars(json);
					log.debug("Candle Type A intraday OK exchange={} token={} hasBars={}",
							exchangeCode, symbolToken, bars);
					if (bars) {
						return Mono.just(json);
					}
					return typeB;
				})
				.onErrorResume(ex -> {
					log.debug("Candle Type A intraday failed ({}), trying Type B: {}",
							exchangeCode + ":" + symbolToken, ex.toString());
					return typeB;
				});
	}

	/** 1=NSE, 2=NFO, 3=CDS, 4=BSE, 5=BFO. */
	public static String intradayExchangeCode(String exchangePrefix) {
		if (exchangePrefix == null || exchangePrefix.isBlank()) {
			return "1";
		}
		return switch (exchangePrefix.trim().toUpperCase()) {
			case "NFO" -> "2";
			case "CDS" -> "3";
			case "BSE" -> "4";
			case "BFO" -> "5";
			default -> "1";
		};
	}

	/**
	 * Prefers Type A; on HTTP error <em>or</em> success with an empty candle array, falls through to Type B.
	 */
	public Mono<JsonNode> getHistoricalCandles(
			String apiKey,
			String accessToken,
			String exchange,
			String symbolToken,
			String typeBInterval,
			String typeAInterval,
			String fromTypeB,
			String toTypeB,
			String fromTypeA,
			String toTypeA) {
		Mono<JsonNode> typeB = Mono.defer(() -> getHistoricalCandlesTypeB(
						apiKey, accessToken, exchange, symbolToken, typeBInterval, fromTypeB, toTypeB)
				.doOnSubscribe(s -> log.debug(
						"Candle Type B GET exchange={} token={} interval={} from={} to={}",
						exchange, symbolToken, typeBInterval, fromTypeB, toTypeB))
				.doOnSuccess(json -> log.debug(
						"Candle Type B OK exchange={} token={} status={} hasBars={}",
						exchange, symbolToken,
						json != null ? json.path("status").asString("") : "",
						hasCandleBars(json))));

		return getHistoricalCandlesTypeA(apiKey, accessToken, exchange, symbolToken, typeAInterval, fromTypeA, toTypeA)
				.doOnSubscribe(s -> log.debug(
						"Candle Type A GET exchange={} token={} interval={} from={} to={}",
						exchange, symbolToken, typeAInterval, fromTypeA, toTypeA))
				.flatMap(json -> {
					boolean bars = hasCandleBars(json);
					log.debug("Candle Type A OK exchange={} token={} status={} hasBars={}",
							exchange, symbolToken,
							json != null ? json.path("status").asString("") : "",
							bars);
					if (bars) {
						return Mono.just(json);
					}
					log.debug("Candle Type A empty bars for {}:{}, trying Type B", exchange, symbolToken);
					return typeB;
				})
				.onErrorResume(ex -> {
					log.debug("Candle Type A failed ({}), trying Type B: {}", exchange + ":" + symbolToken, ex.toString());
					return typeB;
				});
	}

	/** True when response contains a non-empty candle array under common MStock shapes. */
	static boolean hasCandleBars(JsonNode json) {
		if (json == null) {
			return false;
		}
		JsonNode candles = json.path("data").path("candles");
		if (candles.isArray() && !candles.isEmpty()) {
			return true;
		}
		JsonNode data = json.path("data");
		if (data.isArray() && !data.isEmpty()) {
			return true;
		}
		JsonNode top = json.path("candles");
		return top.isArray() && !top.isEmpty();
	}

	/**
	 * Public market-hours feed (NFO/NSE weekDays + specificDate). No Type A auth.
	 * Host: {@link MstockProperties#optionStrategyBaseUrl()}.
	 */
	public Mono<JsonNode> getMarketHours() {
		return optionStrategyGet("/mkt-data/market/market-hours", "MStock market-hours failed: ");
	}

	/**
	 * Public holiday / special-session feed. No Type A auth.
	 * Host: {@link MstockProperties#optionStrategyBaseUrl()}.
	 */
	public Mono<JsonNode> getHolidays(String exch) {
		String exchange = exch == null || exch.isBlank() ? "NSE" : exch.trim();
		return optionStrategyGet(
				"/feed/simulator/get-holiday?exch=" + URLEncoder.encode(exchange, StandardCharsets.UTF_8),
				"MStock holidays failed: ");
	}

	private Mono<JsonNode> optionStrategyGet(String pathAndQuery, String errorPrefix) {
		return Mono.fromCallable(() -> {
					String base = properties.optionStrategyBaseUrl();
					if (base == null || base.isBlank()) {
						base = "https://optionstrategy.mstock.com";
					}
					if (base.endsWith("/")) {
						base = base.substring(0, base.length() - 1);
					}
					String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
					HttpRequest request = HttpRequest.newBuilder()
							.uri(URI.create(base + path))
							.timeout(Duration.ofSeconds(20))
							.GET()
							.header("Accept", "application/json")
							.build();
					HttpResponse<String> response = jdkHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
					int code = response.statusCode();
					String respBody = response.body() != null ? response.body() : "";
					if (code >= 400) {
						throw new BrokerUnavailable(errorPrefix + respBody);
					}
					return jsonMapper.readTree(respBody);
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	private static void authHeaders(org.springframework.http.HttpHeaders headers, String apiKey, String accessToken) {
		headers.set("X-Mirae-Version", "1");
		headers.set("Authorization", "token " + apiKey + ":" + accessToken);
	}

	private boolean isAuthFailure(org.springframework.http.HttpStatusCode status) {
		return status.value() == 401 || status.value() == 403;
	}
}

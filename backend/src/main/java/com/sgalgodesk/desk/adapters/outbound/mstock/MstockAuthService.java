package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.LoginResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class MstockAuthService {

	private final MstockApiClient mstockApiClient;
	private final BrokerSessionService brokerSessionService;

	public Mono<Void> saveApiKey(UUID userId, String apiKey) {
		return brokerSessionService
				.saveCredentials(userId, BrokerId.MSTOCK, apiKey, null)
				.then(brokerSessionService.clearSession(userId, BrokerId.MSTOCK));
	}

	public Mono<Void> clearCredentials(UUID userId) {
		return brokerSessionService.clearCredentials(userId, BrokerId.MSTOCK);
	}

	public Mono<LoginResponse> login(String username, String password) {
		return mstockApiClient.login(username, password)
				.map(json -> new LoginResponse(
						text(json, "status"),
						text(json, "message"),
						json.get("data")));
	}

	public Mono<Void> completeSessionWithOtp(UUID userId, String otp) {
		return brokerSessionService.requireCredentials(userId, BrokerId.MSTOCK)
				.flatMap(creds -> mstockApiClient.sessionToken(creds.clientId(), otp)
						.flatMap(json -> persistSession(userId, json)));
	}

	public Mono<Void> completeSessionWithTotp(UUID userId, String totp) {
		return brokerSessionService.requireCredentials(userId, BrokerId.MSTOCK)
				.flatMap(creds -> mstockApiClient.verifyTotp(creds.clientId(), totp)
						.flatMap(json -> persistSession(userId, json)));
	}

	private Mono<Void> persistSession(UUID userId, JsonNode json) {
		String status = text(json, "status");
		if (status != null && status.equalsIgnoreCase("error")) {
			return Mono.error(new ValidationFailed(
					text(json, "message") != null ? text(json, "message") : "MStock session failed"));
		}
		String accessToken = firstText(json, "access_token", "data.access_token");
		if (accessToken == null || accessToken.isBlank()) {
			JsonNode data = json.get("data");
			if (data != null && data.isObject()) {
				accessToken = text(data, "access_token");
			}
		}
		if (accessToken == null || accessToken.isBlank()) {
			return Mono.error(new BrokerUnavailable("Missing MStock access_token"));
		}
		String userName = firstText(json, "user_name", "data.user_name", "user_shortname");
		if (userName == null || userName.isBlank()) {
			userName = "MStock user";
		}
		String refresh = firstText(json, "refresh_token", "enctoken");
		return brokerSessionService
				.saveSession(userId, BrokerId.MSTOCK, userName, accessToken, refresh)
				.doOnSuccess(saved -> log.info("MStock session saved for appUserId={}", userId))
				.then();
	}

	private static String firstText(JsonNode node, String... paths) {
		for (String path : paths) {
			String value = textPath(node, path);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static String textPath(JsonNode node, String path) {
		if (node == null || path == null) {
			return null;
		}
		if (!path.contains(".")) {
			return text(node, path);
		}
		String[] parts = path.split("\\.");
		JsonNode cur = node;
		for (String part : parts) {
			if (cur == null) {
				return null;
			}
			cur = cur.get(part);
		}
		return cur == null || cur.isNull() ? null : cur.asString();
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}

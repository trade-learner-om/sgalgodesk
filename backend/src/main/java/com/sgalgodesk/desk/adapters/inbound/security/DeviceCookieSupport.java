package com.sgalgodesk.desk.adapters.inbound.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;

import com.sgalgodesk.desk.application.account.DeviceSessionService;

public final class DeviceCookieSupport {

	public static final String COOKIE_NAME = "DEVICE_ID";

	private DeviceCookieSupport() {
	}

	public static UUID readDeviceId(ServerWebExchange exchange) {
		MultiValueMap<String, HttpCookie> cookies = exchange.getRequest().getCookies();
		HttpCookie cookie = cookies.getFirst(COOKIE_NAME);
		if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(cookie.getValue().trim());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	public static void writeDeviceId(ServerWebExchange exchange, UUID deviceId, Instant expiresAt) {
		if (exchange.getResponse().isCommitted()) {
			return;
		}
		Instant now = Instant.now();
		int maxAge = DeviceSessionService.maxAgeSeconds(expiresAt, now);
		ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, deviceId.toString())
				.path("/")
				.httpOnly(true)
				.sameSite("Lax")
				.maxAge(Duration.ofSeconds(maxAge))
				.build();
		exchange.getResponse().addCookie(cookie);
	}

	public static void clearDeviceId(ServerWebExchange exchange) {
		if (exchange.getResponse().isCommitted()) {
			return;
		}
		ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
				.path("/")
				.httpOnly(true)
				.sameSite("Lax")
				.maxAge(Duration.ZERO)
				.build();
		exchange.getResponse().addCookie(cookie);
	}
}

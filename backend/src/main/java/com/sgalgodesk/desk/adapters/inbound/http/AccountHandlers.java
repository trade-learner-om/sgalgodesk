package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.application.account.AccountDtos.LoginRequest;
import com.sgalgodesk.desk.application.account.AccountDtos.SaveSettingsRequest;
import com.sgalgodesk.desk.application.account.AccountDtos.SignupRequest;
import com.sgalgodesk.desk.application.account.AccountService;
import com.sgalgodesk.desk.application.account.AccountService.AuthResult;
import com.sgalgodesk.desk.adapters.inbound.security.DeviceCookieSupport;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AccountHandlers {

	private final AccountService accountService;
	private final Validator validator;

	public Mono<ServerResponse> signup(ServerRequest request) {
		return validatedBody(request, SignupRequest.class, validator)
				.flatMap(body -> accountService.signup(body, DeviceCookieSupport.readDeviceId(request.exchange())))
				.flatMap(result -> {
					writeCookie(request, result);
					return created(result.me());
				});
	}

	public Mono<ServerResponse> login(ServerRequest request) {
		return validatedBody(request, LoginRequest.class, validator)
				.flatMap(body -> accountService.login(body, DeviceCookieSupport.readDeviceId(request.exchange())))
				.flatMap(result -> {
					writeCookie(request, result);
					return ok(result.me());
				});
	}

	public Mono<ServerResponse> logout(ServerRequest request) {
		UUID deviceId = DeviceCookieSupport.readDeviceId(request.exchange());
		DeviceCookieSupport.clearDeviceId(request.exchange());
		return accountService.logout(deviceId).then(noContent());
	}

	public Mono<ServerResponse> me(ServerRequest request) {
		return requireUserId()
				.flatMap(accountService::me)
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> saveSettings(ServerRequest request) {
		return validatedBody(request, SaveSettingsRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> accountService.saveSettings(userId, body)))
				.flatMap(HttpSupport::ok);
	}

	private static void writeCookie(ServerRequest request, AuthResult result) {
		DeviceCookieSupport.writeDeviceId(request.exchange(), result.deviceId(), result.expiresAt());
	}
}

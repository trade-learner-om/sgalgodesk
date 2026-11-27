package com.sgalgodesk.desk.adapters.inbound.http;

import static com.sgalgodesk.desk.adapters.inbound.http.HttpSupport.*;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.LoginRequest;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.SaveApiKeyRequest;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.SessionOtpRequest;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.SessionTotpRequest;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockPrimaryIpService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BrokerMstockHandlers {

	private final MstockAuthService mstockAuthService;
	private final MstockPrimaryIpService mstockPrimaryIpService;
	private final Validator validator;

	public Mono<ServerResponse> primaryIp(ServerRequest request) {
		return mstockPrimaryIpService.resolve().flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> saveCredentials(ServerRequest request) {
		return validatedBody(request, SaveApiKeyRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> mstockAuthService.saveApiKey(userId, body.apiKey())))
				.then(ok());
	}

	public Mono<ServerResponse> clearCredentials(ServerRequest request) {
		return requireUserId().flatMap(mstockAuthService::clearCredentials).then(ok());
	}

	public Mono<ServerResponse> login(ServerRequest request) {
		return validatedBody(request, LoginRequest.class, validator)
				.flatMap(body -> mstockAuthService.login(body.username(), body.password()))
				.flatMap(HttpSupport::ok);
	}

	public Mono<ServerResponse> sessionOtp(ServerRequest request) {
		return validatedBody(request, SessionOtpRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> mstockAuthService.completeSessionWithOtp(userId, body.otp())))
				.then(ok());
	}

	public Mono<ServerResponse> sessionTotp(ServerRequest request) {
		return validatedBody(request, SessionTotpRequest.class, validator)
				.flatMap(body -> requireUserId().flatMap(userId -> mstockAuthService.completeSessionWithTotp(userId, body.totp())))
				.then(ok());
	}
}

package com.sgalgodesk.desk.adapters.inbound.http;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.sgalgodesk.desk.domain.shared.Unauthorized;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;
import com.sgalgodesk.desk.adapters.inbound.security.AppPrincipal;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import reactor.core.publisher.Mono;

/** Shared helpers for functional (RouterFunction) HTTP handlers. */
public final class HttpSupport {

	private HttpSupport() {
	}

	public static Mono<UUID> requireUserId() {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.filter(Authentication::isAuthenticated)
				.map(Authentication::getPrincipal)
				.filter(AppPrincipal.class::isInstance)
				.cast(AppPrincipal.class)
				.map(AppPrincipal::getUserId)
				.switchIfEmpty(Mono.error(new Unauthorized("Not authenticated")));
	}

	// ---- responses ----

	public static Mono<ServerResponse> ok(Object body) {
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	public static Mono<ServerResponse> ok() {
		return ServerResponse.ok().build();
	}

	public static Mono<ServerResponse> created(Object body) {
		return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
	}

	public static Mono<ServerResponse> noContent() {
		return ServerResponse.noContent().build();
	}

	public static Mono<ServerResponse> badRequest(String message) {
		return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("error", message, "status", 400));
	}

	// ---- request parsing ----

	/** Reads the JSON body and applies Bean Validation (equivalent of {@code @Valid @RequestBody}). */
	public static <T> Mono<T> validatedBody(ServerRequest request, Class<T> type, Validator validator) {
		return body(request, type).doOnNext(b -> validate(b, validator));
	}

	/** Reads the JSON body without validation (equivalent of plain {@code @RequestBody}). */
	public static <T> Mono<T> body(ServerRequest request, Class<T> type) {
		return request.bodyToMono(type)
				.switchIfEmpty(Mono.error(new ValidationFailed("Request body is required")));
	}

	private static <T> void validate(T body, Validator validator) {
		var violations = validator.validate(body);
		if (!violations.isEmpty()) {
			String message = violations.stream()
					.map((ConstraintViolation<T> v) -> v.getPropertyPath() + ": " + v.getMessage())
					.sorted()
					.collect(Collectors.joining("; "));
			throw new ValidationFailed(message);
		}
	}

	public static UUID pathUuid(ServerRequest request, String name) {
		return UUID.fromString(request.pathVariable(name));
	}

	public static String requiredParam(ServerRequest request, String name) {
		return request.queryParam(name)
				.orElseThrow(() -> new ValidationFailed("Required parameter '" + name + "' is missing"));
	}

	public static String param(ServerRequest request, String name, String defaultValue) {
		return request.queryParam(name).orElse(defaultValue);
	}

	public static String optionalParam(ServerRequest request, String name) {
		return request.queryParam(name).orElse(null);
	}
}

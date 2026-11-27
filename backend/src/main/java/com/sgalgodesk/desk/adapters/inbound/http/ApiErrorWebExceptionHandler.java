package com.sgalgodesk.desk.adapters.inbound.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.DomainException;
import com.sgalgodesk.desk.domain.shared.Forbidden;
import com.sgalgodesk.desk.domain.shared.NotFound;
import com.sgalgodesk.desk.domain.shared.Unauthorized;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Maps domain / validation / status exceptions to {@code {"error": "...", "status": N}} JSON. */
@Component
@Order(-2)
@RequiredArgsConstructor
public class ApiErrorWebExceptionHandler implements WebExceptionHandler {

	private final JsonMapper jsonMapper;

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		ErrorBody body = map(ex);
		if (body == null || exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}
		var response = exchange.getResponse();
		response.setStatusCode(HttpStatusCode.valueOf(body.status()));
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("error", body.message());
		payload.put("status", body.status());
		byte[] bytes = jsonMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Mono.just(buffer));
	}

	private static ErrorBody map(Throwable ex) {
		if (ex instanceof DomainException de) {
			return new ErrorBody(domainStatus(de).value(), message(de.getMessage(), "Error"));
		}
		if (ex instanceof WebExchangeBindException bind) {
			String message = bind.getFieldErrors().stream()
					.findFirst()
					.map(err -> err.getField() + ": " + err.getDefaultMessage())
					.orElse("Validation failed");
			return new ErrorBody(400, message);
		}
		if (ex instanceof ResponseStatusException rse) {
			return new ErrorBody(rse.getStatusCode().value(),
					rse.getReason() != null ? rse.getReason() : rse.getStatusCode().toString());
		}
		if (ex instanceof IllegalArgumentException iae) {
			return new ErrorBody(400, message(iae.getMessage(), "Bad request"));
		}
		return null;
	}

	private static HttpStatus domainStatus(DomainException ex) {
		return switch (ex) {
			case Unauthorized e -> HttpStatus.UNAUTHORIZED;
			case Forbidden e -> HttpStatus.FORBIDDEN;
			case NotFound e -> HttpStatus.NOT_FOUND;
			case Conflict e -> HttpStatus.CONFLICT;
			case ValidationFailed e -> HttpStatus.BAD_REQUEST;
			case BrokerUnavailable e -> HttpStatus.BAD_GATEWAY;
			case BrokerAuthExpired e -> HttpStatus.UNAUTHORIZED;
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	private static String message(String m, String fallback) {
		return m != null ? m : fallback;
	}

	private record ErrorBody(int status, String message) {
	}
}

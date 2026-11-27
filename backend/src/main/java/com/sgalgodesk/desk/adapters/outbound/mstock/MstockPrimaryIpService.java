package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockAuthDtos.PrimaryIpResponse;
import com.sgalgodesk.desk.config.MstockProperties;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@Slf4j
public class MstockPrimaryIpService {

	private static final Duration CACHE_TTL = Duration.ofHours(1);
	private static final String IPIFY_URL = "https://api.ipify.org?format=json";

	private final MstockProperties properties;
	private final WebClient ipifyClient;
	private final AtomicReference<CachedIp> cache = new AtomicReference<>();

	public MstockPrimaryIpService(
			MstockProperties properties,
			@Qualifier("outboundWebClient") WebClient outboundWebClient) {
		this.properties = properties;
		this.ipifyClient = outboundWebClient;
	}

	public Mono<PrimaryIpResponse> resolve() {
		String configured = properties.primaryIp();
		if (configured != null && !configured.isBlank()) {
			return Mono.just(new PrimaryIpResponse(configured.trim()));
		}

		CachedIp hit = cache.get();
		if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
			return Mono.just(new PrimaryIpResponse(hit.ip()));
		}

		return ipifyClient.get()
				.uri(IPIFY_URL)
				.retrieve()
				.bodyToMono(JsonNode.class)
				.map(json -> {
					JsonNode ipNode = json.get("ip");
					String ip = ipNode == null || ipNode.isNull() ? null : ipNode.asString();
					if (ip == null || ip.isBlank()) {
						throw new BrokerUnavailable("Could not detect server public IP");
					}
					return ip.trim();
				})
				.doOnNext(ip -> cache.set(new CachedIp(ip, Instant.now().plus(CACHE_TTL))))
				.map(PrimaryIpResponse::new)
				.onErrorMap(ex -> {
					if (ex instanceof BrokerUnavailable) {
						return ex;
					}
					log.warn("Failed to detect egress IP via ipify: {}", ex.toString());
					return new BrokerUnavailable(
							"Could not detect server public IP — set mstock.primary-ip");
				});
	}

	private record CachedIp(String ip, Instant expiresAt) {
	}
}

package com.sgalgodesk.desk.adapters.outbound.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.application.account.port.DeviceSessionPort;
import com.sgalgodesk.desk.domain.account.DeviceSession;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis-backed device sessions. Keys expire at the session {@code expiresAt}
 * (next 07:59:59 Asia/Kolkata).
 *
 * <ul>
 *   <li>{@code app_session:device:{deviceId}} — JSON session payload</li>
 *   <li>{@code app_session:user:{userId}} — SET of deviceId strings</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DeviceSessionRedisAdapter implements DeviceSessionPort {

	private final ReactiveStringRedisTemplate redis;
	private final JsonMapper jsonMapper;

	@Override
	public Mono<DeviceSession> findByDeviceId(UUID deviceId) {
		if (deviceId == null) {
			return Mono.empty();
		}
		return redis.opsForValue()
				.get(deviceKey(deviceId))
				.map(this::deserialize);
	}

	@Override
	public Mono<DeviceSession> save(DeviceSession session) {
		Duration ttl = ttlUntil(session.expiresAt());
		String json = serialize(session);
		String dKey = deviceKey(session.deviceId());
		String uKey = userKey(session.userId());
		return redis.opsForValue()
				.set(dKey, json, ttl)
				.then(redis.opsForSet().add(uKey, session.deviceId().toString()))
				.then(redis.expire(uKey, ttl))
				.thenReturn(session);
	}

	@Override
	public Mono<Void> deleteByDeviceId(UUID deviceId) {
		if (deviceId == null) {
			return Mono.empty();
		}
		String dKey = deviceKey(deviceId);
		return redis.opsForValue()
				.get(dKey)
				.flatMap(json -> {
					DeviceSession session = deserialize(json);
					return redis.delete(dKey)
							.then(redis.opsForSet().remove(userKey(session.userId()), deviceId.toString()))
							.then();
				})
				.switchIfEmpty(redis.delete(dKey).then());
	}

	@Override
	public Mono<Void> deleteByUserId(UUID userId) {
		if (userId == null) {
			return Mono.empty();
		}
		String uKey = userKey(userId);
		return redis.opsForSet()
				.members(uKey)
				.flatMap(deviceIdStr -> redis.delete(deviceKey(UUID.fromString(deviceIdStr))))
				.then(redis.delete(uKey))
				.then();
	}

	static String deviceKey(UUID deviceId) {
		return "app_session:device:" + deviceId;
	}

	static String userKey(UUID userId) {
		return "app_session:user:" + userId;
	}

	static Duration ttlUntil(Instant expiresAt) {
		long seconds = Duration.between(Instant.now(), expiresAt).getSeconds();
		return Duration.ofSeconds(Math.max(1L, seconds));
	}

	private String serialize(DeviceSession session) {
		return jsonMapper.writeValueAsString(session);
	}

	private DeviceSession deserialize(String json) {
		return jsonMapper.readValue(json, DeviceSession.class);
	}
}

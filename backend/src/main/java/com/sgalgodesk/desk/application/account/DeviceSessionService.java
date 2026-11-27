package com.sgalgodesk.desk.application.account;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.account.port.DeviceSessionPort;
import com.sgalgodesk.desk.application.account.port.UserRepositoryPort;
import com.sgalgodesk.desk.domain.account.DeviceSession;
import com.sgalgodesk.desk.domain.account.User;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class DeviceSessionService {

	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	public static final LocalTime DAILY_CUTOFF = LocalTime.of(7, 59, 59);

	private final DeviceSessionPort deviceSessionPort;
	private final UserRepositoryPort userRepositoryPort;

	public record EstablishedSession(UUID deviceId, Instant expiresAt) {
	}

	public record ResolvedSession(User user, UUID deviceId, Instant expiresAt) {
	}

	/** Next 07:59:59 Asia/Kolkata strictly after {@code now}. */
	public static Instant nextExpiry(Instant now) {
		ZonedDateTime zoned = now.atZone(IST);
		LocalDate day = zoned.toLocalDate();
		ZonedDateTime todayCutoff = ZonedDateTime.of(day, DAILY_CUTOFF, IST);
		if (!zoned.isBefore(todayCutoff)) {
			return todayCutoff.plusDays(1).toInstant();
		}
		return todayCutoff.toInstant();
	}

	public static int maxAgeSeconds(Instant expiresAt, Instant now) {
		long seconds = Duration.between(now, expiresAt).getSeconds();
		return (int) Math.max(1L, Math.min(seconds, Integer.MAX_VALUE));
	}

	/**
	 * Create or refresh a device session for the user.
	 * Reuses {@code existingDeviceId} when it already belongs to this user.
	 */
	public Mono<EstablishedSession> establish(User user, UUID existingDeviceId) {
		Instant now = Instant.now();
		Instant expiresAt = nextExpiry(now);
		UUID userId = user.id();

		if (existingDeviceId != null) {
			return deviceSessionPort.findByDeviceId(existingDeviceId)
					.flatMap(session -> {
						if (userId.equals(session.userId())) {
							DeviceSession refreshed = new DeviceSession(
									session.id(), session.userId(), session.deviceId(),
									expiresAt, session.createdAt(), now);
							return deviceSessionPort.save(refreshed)
									.map(saved -> new EstablishedSession(saved.deviceId(), saved.expiresAt()));
						}
						// Device id already bound to another account — mint a new one.
						return createNew(userId, expiresAt, now);
					})
					.switchIfEmpty(createNew(userId, expiresAt, now));
		}
		return createNew(userId, expiresAt, now);
	}

	/**
	 * Read-only auth lookup: validate expiry, load user. Does not update {@code last_seen_at}
	 * (avoids a Redis write on every request / WebSocket upgrade path).
	 * Expired keys are deleted asynchronously so the filter is not blocked on the write;
	 * Redis TTL also drops keys at the daily cutoff.
	 */
	public Mono<ResolvedSession> resolve(UUID deviceId) {
		if (deviceId == null) {
			return Mono.empty();
		}
		Instant now = Instant.now();
		return deviceSessionPort.findByDeviceId(deviceId)
				.flatMap(session -> {
					if (!session.isValid(now)) {
						deviceSessionPort.deleteByDeviceId(deviceId).subscribe();
						return Mono.empty();
					}
					return userRepositoryPort.findById(session.userId())
							.map(user -> new ResolvedSession(user, session.deviceId(), session.expiresAt()));
				});
	}

	public Mono<Void> invalidate(UUID deviceId) {
		if (deviceId == null) {
			return Mono.empty();
		}
		return deviceSessionPort.deleteByDeviceId(deviceId);
	}

	private Mono<EstablishedSession> createNew(UUID userId, Instant expiresAt, Instant now) {
		UUID deviceId = UUID.randomUUID();
		DeviceSession session = new DeviceSession(
				UUID.randomUUID(),
				userId,
				deviceId,
				expiresAt,
				now,
				now);
		return deviceSessionPort.save(session)
				.map(saved -> new EstablishedSession(saved.deviceId(), saved.expiresAt()));
	}
}

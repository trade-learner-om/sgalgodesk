package com.sgalgodesk.desk.application.broker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.account.port.UserSettingsPort;
import com.sgalgodesk.desk.application.broker.port.BrokerCredentialsPort;
import com.sgalgodesk.desk.application.broker.port.BrokerSessionPort;
import com.sgalgodesk.desk.domain.account.UserSettings;
import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;
import com.sgalgodesk.desk.domain.shared.BrokerAuthExpired;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BrokerSessionService {

	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final BrokerSessionPort brokerSessionPort;
	private final BrokerCredentialsPort brokerCredentialsPort;
	private final UserSettingsPort userSettingsPort;
	private final java.util.List<ActiveBrokerListener> activeBrokerListeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	public Mono<BrokerId> activeBroker(UUID userId) {
		return userSettingsPort.findByUserId(userId)
				.flatMap(settings -> settings.activeBroker() == null
						? Mono.<BrokerId>empty()
						: Mono.just(settings.activeBroker()))
				.switchIfEmpty(Mono.just(BrokerId.MSTOCK));
	}

	public Mono<BrokerId> requireActiveBroker(UUID userId) {
		return activeBroker(userId)
				.flatMap(broker -> requireValidSession(userId, broker).thenReturn(broker));
	}

	public void addActiveBrokerListener(ActiveBrokerListener listener) {
		if (listener != null) {
			activeBrokerListeners.add(listener);
		}
	}

	public Mono<Void> setActiveBroker(UUID userId, BrokerId broker) {
		return requireValidSession(userId, broker)
				.then(Mono.defer(() -> upsertSettings(userId, broker, true)))
				.doOnSuccess(saved -> {
					for (ActiveBrokerListener listener : activeBrokerListeners) {
						listener.onActiveBrokerChanged(userId, broker);
					}
				})
				.then();
	}

	@FunctionalInterface
	public interface ActiveBrokerListener {
		void onActiveBrokerChanged(UUID userId, BrokerId broker);
	}

	public Mono<BrokerSession> requireValidSession(UUID userId, BrokerId broker) {
		return brokerSessionPort.findByUserIdAndBroker(userId, broker)
				.switchIfEmpty(Mono.error(new Conflict(brokerRequiredCode(broker))))
				.flatMap(session -> {
					if (!session.isValid(Instant.now())) {
						return Mono.error(new Conflict(tokenExpiredCode(broker)));
					}
					return Mono.just(session);
				});
	}

	public Mono<BrokerSession> requireActiveSession(UUID userId) {
		return activeBroker(userId).flatMap(broker -> requireValidSession(userId, broker));
	}

	public Mono<String> requireAccessToken(UUID userId) {
		return requireActiveSession(userId).map(BrokerSession::accessToken);
	}

	public Mono<String> requireAccessToken(UUID userId, BrokerId broker) {
		return requireValidSession(userId, broker).map(BrokerSession::accessToken);
	}

	public Mono<BrokerCredentials> requireCredentials(UUID userId, BrokerId broker) {
		return brokerCredentialsPort.findByUserIdAndBroker(userId, broker)
				.switchIfEmpty(Mono.error(new Conflict(credentialsRequiredCode(broker))));
	}

	public Mono<Boolean> hasCredentials(UUID userId, BrokerId broker) {
		return brokerCredentialsPort.existsByUserIdAndBroker(userId, broker);
	}

	public Flux<BrokerCredentials> listCredentials(UUID userId) {
		return brokerCredentialsPort.findByUserId(userId);
	}

	public Flux<BrokerSession> listSessions(UUID userId) {
		return brokerSessionPort.findByUserId(userId);
	}

	public Mono<BrokerCredentials> saveCredentials(
			UUID userId, BrokerId broker, String clientId, String clientSecret) {
		String trimmedId = clientId == null ? "" : clientId.trim();
		String trimmedSecret = clientSecret == null ? null : clientSecret.trim();
		if (trimmedId.isEmpty()) {
			return Mono.error(new ValidationFailed("clientId/apiKey required"));
		}
		Instant now = Instant.now();
		return brokerCredentialsPort.findByUserIdAndBroker(userId, broker)
				.map(existing -> new BrokerCredentials(
						existing.id(), userId, broker, trimmedId, trimmedSecret, existing.createdAt(), now))
				.switchIfEmpty(Mono.fromSupplier(() -> new BrokerCredentials(
						UUID.randomUUID(), userId, broker, trimmedId, trimmedSecret, now, now)))
				.flatMap(brokerCredentialsPort::save);
	}

	public Mono<Void> clearCredentials(UUID userId, BrokerId broker) {
		return brokerCredentialsPort.deleteByUserIdAndBroker(userId, broker)
				.then(clearSession(userId, broker));
	}

	public Mono<BrokerSession> saveSession(
			UUID userId,
			BrokerId broker,
			String displayName,
			String accessToken,
			String refreshToken) {
		Instant now = Instant.now();
		Instant expiresAt = expiryFor(broker, now);
		return brokerSessionPort.findByUserIdAndBroker(userId, broker)
				.map(existing -> new BrokerSession(
						existing.id(), userId, broker, accessToken, refreshToken, displayName,
						expiresAt, existing.createdAt(), now))
				.switchIfEmpty(Mono.fromSupplier(() -> new BrokerSession(
						UUID.randomUUID(), userId, broker, accessToken, refreshToken, displayName,
						expiresAt, now, now)))
				.flatMap(brokerSessionPort::save)
				.flatMap(saved -> ensureActiveBrokerDefault(userId, broker).thenReturn(saved));
	}

	public Mono<Void> markExpired(UUID userId, BrokerId broker) {
		return brokerSessionPort.findByUserIdAndBroker(userId, broker)
				.flatMap(session -> brokerSessionPort.save(new BrokerSession(
						session.id(), session.userId(), session.broker(), session.accessToken(),
						session.refreshToken(), session.displayName(), Instant.EPOCH,
						session.createdAt(), Instant.now())))
				.then();
	}

	public Mono<Void> clearSession(UUID userId, BrokerId broker) {
		return brokerSessionPort.deleteByUserIdAndBroker(userId, broker);
	}

	public <T> Mono<T> translateAuthErrors(UUID userId, BrokerId broker, Mono<T> mono) {
		return mono.onErrorResume(ex -> {
			if (ex instanceof BrokerAuthExpired) {
				return markExpired(userId, broker)
						.then(Mono.error(new Conflict(tokenExpiredCode(broker))));
			}
			return Mono.error(ex);
		});
	}

	public <T> Mono<T> translateActiveAuthErrors(UUID userId, Mono<T> mono) {
		return activeBroker(userId)
				.flatMap(broker -> translateAuthErrors(userId, broker, mono));
	}

	private Mono<Void> ensureActiveBrokerDefault(UUID userId, BrokerId broker) {
		return userSettingsPort.findByUserId(userId)
				.filter(settings -> settings.activeBroker() != null)
				.switchIfEmpty(Mono.defer(() -> upsertSettings(userId, broker, false)))
				.then();
	}

	/** Sets the active broker on the user's settings row, creating it when absent. */
	private Mono<UserSettings> upsertSettings(UUID userId, BrokerId broker, boolean overwrite) {
		Instant now = Instant.now();
		return userSettingsPort.findByUserId(userId)
				.map(existing -> new UserSettings(
						userId, existing.instrumentKey(), existing.tradingSymbol(), existing.assetClass(),
						overwrite || existing.activeBroker() == null ? broker : existing.activeBroker(), now))
				.switchIfEmpty(Mono.fromSupplier(() -> new UserSettings(userId, null, null, null, broker, now)))
				.flatMap(userSettingsPort::save);
	}

	private static Instant expiryFor(BrokerId broker, Instant now) {
		LocalDate todayIst = now.atZone(IST).toLocalDate();
		return todayIst.plusDays(1).atStartOfDay(IST).toInstant();
	}

	private static String brokerRequiredCode(BrokerId broker) {
		return "broker_required";
	}

	private static String credentialsRequiredCode(BrokerId broker) {
		return "broker_credentials_required";
	}

	private static String tokenExpiredCode(BrokerId broker) {
		return "broker_token_expired";
	}
}

package com.sgalgodesk.desk.application.account;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.account.AccountDtos.BrokerStatus;
import com.sgalgodesk.desk.application.account.AccountDtos.LoginRequest;
import com.sgalgodesk.desk.application.account.AccountDtos.MeResponse;
import com.sgalgodesk.desk.application.account.AccountDtos.PreferredUnderlying;
import com.sgalgodesk.desk.application.account.AccountDtos.SaveSettingsRequest;
import com.sgalgodesk.desk.application.account.AccountDtos.SignupRequest;
import com.sgalgodesk.desk.application.account.port.UserRepositoryPort;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.domain.account.User;
import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;
import com.sgalgodesk.desk.domain.shared.Conflict;
import com.sgalgodesk.desk.domain.shared.NotFound;
import com.sgalgodesk.desk.domain.shared.Unauthorized;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AccountService {

	private final UserRepositoryPort userRepositoryPort;
	private final BrokerSessionService brokerSessionService;
	private final UserSettingsService userSettingsService;
	private final DeviceSessionService deviceSessionService;
	private final PasswordEncoder passwordEncoder;

	/** Outcome of signup/login: the "me" payload plus the device session the caller must persist as a cookie. */
	public record AuthResult(MeResponse me, UUID deviceId, Instant expiresAt) {
	}

	public Mono<AuthResult> signup(SignupRequest request, UUID existingDeviceId) {
		String email = request.email().trim().toLowerCase();
		return userRepositoryPort.existsByEmail(email)
				.flatMap(exists -> {
					if (Boolean.TRUE.equals(exists)) {
						return Mono.error(new Conflict("Email already registered"));
					}
					User user = new User(
							UUID.randomUUID(),
							email,
							passwordEncoder.encode(request.password()),
							Instant.now());
					return userRepositoryPort.save(user);
				})
				.flatMap(user -> establishSession(user, existingDeviceId));
	}

	public Mono<AuthResult> login(LoginRequest request, UUID existingDeviceId) {
		String email = request.email().trim().toLowerCase();
		return userRepositoryPort.findByEmail(email)
				.switchIfEmpty(Mono.error(new Unauthorized("Invalid email or password")))
				.flatMap(user -> {
					if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
						return Mono.error(new Unauthorized("Invalid email or password"));
					}
					return establishSession(user, existingDeviceId);
				});
	}

	public Mono<Void> logout(UUID deviceId) {
		return deviceSessionService.invalidate(deviceId);
	}

	public Mono<MeResponse> me(UUID userId) {
		return userRepositoryPort.findById(userId)
				.switchIfEmpty(Mono.error(new NotFound("User not found")))
				.flatMap(user -> buildMe(user.id(), user.email()));
	}

	private Mono<MeResponse> buildMe(UUID userId, String email) {
		Mono<List<BrokerCredentials>> creds =
				brokerSessionService.listCredentials(userId).collectList();
		Mono<List<BrokerSession>> sessions =
				brokerSessionService.listSessions(userId).collectList();
		Mono<BrokerId> active = brokerSessionService.activeBroker(userId);
		Mono<Optional<PreferredUnderlying>> preferred =
				userSettingsService.findPreferred(userId)
						.map(Optional::of)
						.defaultIfEmpty(Optional.empty());
		return Mono.zip(creds, sessions, active, preferred)
				.map(tuple -> buildMe(
						email,
						tuple.getT1(),
						tuple.getT2(),
						tuple.getT3(),
						tuple.getT4().orElse(null)));
	}

	public Mono<PreferredUnderlying> saveSettings(UUID userId, SaveSettingsRequest request) {
		Mono<Void> activeMono = Mono.empty();
		if (request.activeBroker() != null && !request.activeBroker().isBlank()) {
			BrokerId broker = BrokerId.from(request.activeBroker());
			if (broker == null) {
				return Mono.error(new ValidationFailed("Invalid activeBroker"));
			}
			activeMono = brokerSessionService.setActiveBroker(userId, broker);
		}
		boolean hasUnderlying = request.instrumentKey() != null && !request.instrumentKey().isBlank()
				&& request.tradingSymbol() != null && !request.tradingSymbol().isBlank();
		Mono<PreferredUnderlying> preferredMono = hasUnderlying
				? userSettingsService.savePreferred(userId, request)
				: userSettingsService.findPreferred(userId)
						.defaultIfEmpty(new PreferredUnderlying(null, null, null));
		return activeMono.then(preferredMono);
	}

	private static MeResponse buildMe(
			String email,
			List<BrokerCredentials> credentials,
			List<BrokerSession> sessions,
			BrokerId activeBroker,
			PreferredUnderlying preferredUnderlying) {
		Instant now = Instant.now();
		Map<String, BrokerCredentials> credByBroker = credentials.stream()
				.collect(Collectors.toMap(c -> c.broker().code(), Function.identity(), (a, b) -> a));
		Map<String, BrokerSession> sessionByBroker = sessions.stream()
				.collect(Collectors.toMap(x -> x.broker().code(), Function.identity(), (a, b) -> a));

		List<BrokerStatus> brokers = new ArrayList<>();
		for (BrokerId id : BrokerId.values()) {
			BrokerCredentials cred = credByBroker.get(id.code());
			BrokerSession session = sessionByBroker.get(id.code());
			boolean linked = session != null;
			boolean tokenValid = session != null && session.isValid(now);
			brokers.add(new BrokerStatus(
					id.code(),
					cred != null,
					linked,
					tokenValid,
					session != null ? session.displayName() : null));
		}

		BrokerStatus mstock = brokers.stream()
				.filter(b -> BrokerId.MSTOCK.code().equals(b.id()))
				.findFirst()
				.orElse(new BrokerStatus(BrokerId.MSTOCK.code(), false, false, false, null));
		BrokerStatus requested = brokers.stream()
				.filter(b -> activeBroker.code().equals(b.id()))
				.findFirst()
				.orElse(mstock);
		BrokerStatus active = requested;
		String resolvedActive = activeBroker.code();
		if (!requested.tokenValid()) {
			BrokerStatus fallback = brokers.stream()
					.filter(BrokerStatus::tokenValid)
					.findFirst()
					.orElse(requested);
			active = fallback;
			resolvedActive = fallback.id();
		}

		return new MeResponse(
				email,
				mstock.credentialsConfigured(),
				mstock.linked(),
				active.userName() != null ? active.userName() : mstock.userName(),
				active.tokenValid(),
				preferredUnderlying,
				resolvedActive,
				brokers);
	}

	/** Persist the device session, then build the "me" payload for the new user. */
	private Mono<AuthResult> establishSession(User user, UUID existingDeviceId) {
		return deviceSessionService.establish(user, existingDeviceId)
				.flatMap(established -> buildMe(user.id(), user.email())
						.map(me -> new AuthResult(me, established.deviceId(), established.expiresAt())));
	}
}

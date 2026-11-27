package com.sgalgodesk.desk.application.account;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.account.AccountDtos.PreferredUnderlying;
import com.sgalgodesk.desk.application.account.AccountDtos.SaveSettingsRequest;
import com.sgalgodesk.desk.application.account.port.UserSettingsPort;
import com.sgalgodesk.desk.domain.account.UserSettings;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

	private final UserSettingsPort userSettingsPort;

	public Mono<PreferredUnderlying> findPreferred(UUID userId) {
		return userSettingsPort.findByUserId(userId)
				.flatMap(settings -> {
					PreferredUnderlying preferred = toPreferred(settings);
					return preferred == null ? Mono.empty() : Mono.just(preferred);
				});
	}

	public Mono<PreferredUnderlying> savePreferred(UUID userId, SaveSettingsRequest request) {
		Instant now = Instant.now();
		return userSettingsPort.findByUserId(userId)
				.map(existing -> merge(existing, request, now))
				.switchIfEmpty(Mono.fromSupplier(() -> merge(
						new UserSettings(userId, null, null, null, null, now), request, now)))
				.flatMap(userSettingsPort::save)
				.flatMap(saved -> {
					PreferredUnderlying preferred = toPreferred(saved);
					return preferred == null ? Mono.empty() : Mono.just(preferred);
				});
	}

	private static UserSettings merge(UserSettings existing, SaveSettingsRequest request, Instant now) {
		String key = existing.instrumentKey();
		String symbol = existing.tradingSymbol();
		if (request.instrumentKey() != null && !request.instrumentKey().isBlank()) {
			key = request.instrumentKey().trim();
		}
		if (request.tradingSymbol() != null && !request.tradingSymbol().isBlank()) {
			symbol = request.tradingSymbol().trim();
		}
		String assetClass = request.assetClass() == null || request.assetClass().isBlank()
				? existing.assetClass()
				: request.assetClass().trim();
		return new UserSettings(existing.userId(), key, symbol, assetClass, existing.activeBroker(), now);
	}

	private static PreferredUnderlying toPreferred(UserSettings settings) {
		if (settings.instrumentKey() == null || settings.instrumentKey().isBlank()) {
			return null;
		}
		return new PreferredUnderlying(settings.instrumentKey(), settings.tradingSymbol(), settings.assetClass());
	}
}

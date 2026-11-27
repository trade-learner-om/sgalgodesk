package com.sgalgodesk.desk.application.account.port;

import java.util.UUID;

import com.sgalgodesk.desk.domain.account.UserSettings;

import reactor.core.publisher.Mono;

/** Outbound port: persistence of per-user settings. */
public interface UserSettingsPort {

	Mono<UserSettings> findByUserId(UUID userId);

	/** Inserts or updates settings for {@code settings.userId()}. */
	Mono<UserSettings> save(UserSettings settings);
}

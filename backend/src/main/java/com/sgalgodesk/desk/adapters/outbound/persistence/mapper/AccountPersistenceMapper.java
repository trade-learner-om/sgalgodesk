package com.sgalgodesk.desk.adapters.outbound.persistence.mapper;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.AppUserEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.UserSettingsEntity;
import com.sgalgodesk.desk.domain.account.User;
import com.sgalgodesk.desk.domain.account.UserSettings;
import com.sgalgodesk.desk.domain.broker.BrokerId;

/**
 * Maps account persistence entities to and from pure domain models.
 * Entities returned by {@code toEntity} are flagged as new (insert); callers that
 * update an existing row must call {@code markPersisted()} on the result.
 */
public final class AccountPersistenceMapper {

	private AccountPersistenceMapper() {
	}

	public static User toDomain(AppUserEntity entity) {
		if (entity == null) {
			return null;
		}
		return new User(entity.getId(), entity.getEmail(), entity.getPasswordHash(), entity.getCreatedAt());
	}

	public static AppUserEntity toEntity(User user) {
		if (user == null) {
			return null;
		}
		return new AppUserEntity(user.id(), user.email(), user.passwordHash(), user.createdAt());
	}

	public static UserSettings toDomain(UserSettingsEntity entity) {
		if (entity == null) {
			return null;
		}
		return new UserSettings(
				entity.getUserId(),
				entity.getPreferredUnderlyingKey(),
				entity.getPreferredUnderlyingSymbol(),
				entity.getPreferredAssetClass(),
				BrokerId.from(entity.getActiveBroker()),
				entity.getUpdatedAt());
	}

	public static UserSettingsEntity toEntity(UserSettings settings) {
		if (settings == null) {
			return null;
		}
		UserSettingsEntity entity = new UserSettingsEntity();
		entity.setUserId(settings.userId());
		entity.setPreferredUnderlyingKey(settings.instrumentKey());
		entity.setPreferredUnderlyingSymbol(settings.tradingSymbol());
		entity.setPreferredAssetClass(settings.assetClass());
		entity.setActiveBroker(settings.activeBroker() == null ? null : settings.activeBroker().code());
		entity.setUpdatedAt(settings.updatedAt());
		entity.markNew();
		return entity;
	}
}

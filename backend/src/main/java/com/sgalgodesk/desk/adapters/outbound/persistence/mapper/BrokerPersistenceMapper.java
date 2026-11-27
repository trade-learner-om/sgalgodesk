package com.sgalgodesk.desk.adapters.outbound.persistence.mapper;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerCredentialsEntity;
import com.sgalgodesk.desk.adapters.outbound.persistence.entity.BrokerSessionEntity;
import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;

/**
 * Maps broker persistence entities to and from pure domain models.
 *
 * <p>The tables have no {@code created_at} column, so {@code createdAt} is derived from
 * {@code updated_at} when reading and ignored when writing. Columns the domain does not model
 * ({@code extra_json}, {@code broker_user_id}) are preserved by the {@code toEntity(domain, existing)}
 * overloads, which copy domain values onto an existing (persisted) entity, or create a new one if null.
 */
public final class BrokerPersistenceMapper {

	private BrokerPersistenceMapper() {
	}

	public static BrokerCredentials toDomain(BrokerCredentialsEntity entity) {
		if (entity == null) {
			return null;
		}
		return new BrokerCredentials(
				entity.getId(),
				entity.getUserId(),
				BrokerId.from(entity.getBroker()),
				entity.getClientId(),
				entity.getClientSecret(),
				entity.getUpdatedAt(),
				entity.getUpdatedAt());
	}

	/** Creates a new (insert) entity. */
	public static BrokerCredentialsEntity toEntity(BrokerCredentials credentials) {
		return toEntity(credentials, null);
	}

	/** Applies domain values to {@code existing} (kept as persisted), or creates a new entity if null. */
	public static BrokerCredentialsEntity toEntity(BrokerCredentials credentials, BrokerCredentialsEntity existing) {
		if (credentials == null) {
			return null;
		}
		BrokerCredentialsEntity entity = existing != null ? existing : new BrokerCredentialsEntity();
		entity.setId(credentials.id());
		entity.setUserId(credentials.userId());
		entity.setBroker(credentials.broker() == null ? null : credentials.broker().code());
		entity.setClientId(credentials.clientId());
		entity.setClientSecret(credentials.clientSecret());
		entity.setUpdatedAt(credentials.updatedAt());
		if (existing == null) {
			entity.markNew();
		}
		return entity;
	}

	public static BrokerSession toDomain(BrokerSessionEntity entity) {
		if (entity == null) {
			return null;
		}
		return new BrokerSession(
				entity.getId(),
				entity.getUserId(),
				BrokerId.from(entity.getBroker()),
				entity.getAccessToken(),
				entity.getRefreshMeta(),
				entity.getDisplayName(),
				entity.getExpiresAt(),
				entity.getUpdatedAt(),
				entity.getUpdatedAt());
	}

	/** Creates a new (insert) entity. */
	public static BrokerSessionEntity toEntity(BrokerSession session) {
		return toEntity(session, null);
	}

	/** Applies domain values to {@code existing} (kept as persisted), or creates a new entity if null. */
	public static BrokerSessionEntity toEntity(BrokerSession session, BrokerSessionEntity existing) {
		if (session == null) {
			return null;
		}
		BrokerSessionEntity entity = existing != null ? existing : new BrokerSessionEntity();
		entity.setId(session.id());
		entity.setUserId(session.userId());
		entity.setBroker(session.broker() == null ? null : session.broker().code());
		entity.setAccessToken(session.accessToken());
		entity.setRefreshMeta(session.refreshToken());
		entity.setDisplayName(session.displayName());
		entity.setExpiresAt(session.expiresAt());
		entity.setUpdatedAt(session.updatedAt());
		if (existing == null) {
			// broker_user_id is NOT NULL but not modelled in the domain; default to the broker code.
			entity.setBrokerUserId(session.broker() == null ? "unknown" : session.broker().code().toLowerCase());
			entity.markNew();
		}
		return entity;
	}
}

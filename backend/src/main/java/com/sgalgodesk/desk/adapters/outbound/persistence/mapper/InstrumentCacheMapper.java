package com.sgalgodesk.desk.adapters.outbound.persistence.mapper;

import java.nio.charset.StandardCharsets;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.InstrumentCacheEntity;
import com.sgalgodesk.desk.domain.instruments.InstrumentCache;

/**
 * Maps {@link InstrumentCacheEntity} (binary payload) to {@link InstrumentCache} (JSON string).
 * The payload bytes are treated as UTF-8 JSON. The domain has no instrument count, so it is
 * supplied by the caller when writing; {@code loadedAt} maps to {@code updatedAt}.
 */
public final class InstrumentCacheMapper {

	private InstrumentCacheMapper() {
	}

	public static InstrumentCache toDomain(InstrumentCacheEntity entity) {
		if (entity == null) {
			return null;
		}
		String json = entity.getPayload() == null ? null : new String(entity.getPayload(), StandardCharsets.UTF_8);
		return new InstrumentCache(entity.getAsOfDate(), json, entity.getLoadedAt());
	}

	/** Creates a new (insert) entity with a null instrument count. */
	public static InstrumentCacheEntity toEntity(InstrumentCache cache) {
		return toEntity(cache, null, true);
	}

	public static InstrumentCacheEntity toEntity(InstrumentCache cache, Integer instrumentCount, boolean isNew) {
		if (cache == null) {
			return null;
		}
		InstrumentCacheEntity entity = new InstrumentCacheEntity();
		entity.setAsOfDate(cache.asOfDate());
		entity.setPayload(cache.payloadJson() == null ? null : cache.payloadJson().getBytes(StandardCharsets.UTF_8));
		entity.setInstrumentCount(instrumentCount);
		entity.setLoadedAt(cache.updatedAt());
		entity.setNewEntity(isNew);
		return entity;
	}
}

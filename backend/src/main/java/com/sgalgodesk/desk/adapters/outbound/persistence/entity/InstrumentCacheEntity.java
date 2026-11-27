package com.sgalgodesk.desk.adapters.outbound.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("instrument_cache")
@Getter
@Setter
@NoArgsConstructor
public class InstrumentCacheEntity implements Persistable<LocalDate> {

	@Id
	@Column("as_of_date")
	private LocalDate asOfDate;

	@Column("payload")
	private byte[] payload;

	@Column("instrument_count")
	private Integer instrumentCount;

	@Column("loaded_at")
	private Instant loadedAt;

	@Transient
	private boolean newEntity;

	@Override
	public LocalDate getId() {
		return asOfDate;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	public void markNew() {
		this.newEntity = true;
	}

	public void markPersisted() {
		this.newEntity = false;
	}
}

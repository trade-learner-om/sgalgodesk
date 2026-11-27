package com.sgalgodesk.desk.adapters.outbound.persistence.repository;

import java.time.LocalDate;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import com.sgalgodesk.desk.adapters.outbound.persistence.entity.InstrumentCacheEntity;

public interface InstrumentCacheEntityRepository extends ReactiveCrudRepository<InstrumentCacheEntity, LocalDate> {
}

package com.sgalgodesk.desk.domain.instruments;

import java.time.Instant;
import java.time.LocalDate;

public record InstrumentCache(LocalDate asOfDate, String payloadJson, Instant updatedAt) {
}

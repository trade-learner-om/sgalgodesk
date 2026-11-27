package com.sgalgodesk.desk.application.instruments;

import java.time.LocalDate;

public record InstrumentSearchHit(
		String instrumentKey,
		String tradingSymbol,
		String name,
		String kind,
		Double strike,
		LocalDate expiry,
		Integer lotSize,
		Double tickSize,
		String underlyingKey,
		String underlyingSymbol,
		String assetClass
) {
}

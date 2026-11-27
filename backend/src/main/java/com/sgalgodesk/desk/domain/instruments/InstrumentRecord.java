package com.sgalgodesk.desk.domain.instruments;

import java.time.LocalDate;

public record InstrumentRecord(
		String instrumentKey,
		String tradingSymbol,
		String name,
		String instrumentType,
		String segment,
		String exchange,
		String underlyingSymbol,
		String underlyingKey,
		String underlyingType,
		String assetClass,
		Double strikePrice,
		LocalDate expiry,
		Integer lotSize,
		Double tickSize
) {
}

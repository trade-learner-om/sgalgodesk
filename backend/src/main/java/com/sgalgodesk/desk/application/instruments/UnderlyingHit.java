package com.sgalgodesk.desk.application.instruments;

public record UnderlyingHit(
		String instrumentKey,
		String tradingSymbol,
		String name,
		String assetClass,
		Integer lotSize
) {
}

package com.sgalgodesk.desk.application.instruments;

import java.util.List;

public final class InstrumentsDtos {

	private InstrumentsDtos() {
	}

	public record TradableInstrument(
			String symbol,
			Integer lotSize,
			String underlyingKey) {
	}

	public record TradableInstrumentsResponse(List<TradableInstrument> instruments) {
	}
}

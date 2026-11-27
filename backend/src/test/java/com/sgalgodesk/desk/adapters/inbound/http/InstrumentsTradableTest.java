package com.sgalgodesk.desk.adapters.inbound.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.instruments.InstrumentsDtos.TradableInstrument;
import com.sgalgodesk.desk.application.instruments.InstrumentsDtos.TradableInstrumentsResponse;
import com.sgalgodesk.desk.config.InstrumentsProperties;
import com.sgalgodesk.desk.config.InstrumentsProperties.InstrumentDefault;

/**
 * Shape of tradable payload built from InstrumentsProperties (handler logic without Spring context).
 */
class InstrumentsTradableTest {

	@Test
	void buildsTradableResponseFromConfig() {
		InstrumentsProperties props = new InstrumentsProperties();
		props.setTradable("NIFTY");
		InstrumentDefault nifty = new InstrumentDefault();
		nifty.setLotSize(65);
		props.setDefaults(Map.of("NIFTY", nifty));

		List<TradableInstrument> list = props.tradableSymbols().stream()
				.map(symbol -> new TradableInstrument(
						symbol,
						props.configuredLotSize(symbol),
						InstrumentsProperties.underlyingKeyForSymbol(symbol)))
				.toList();
		TradableInstrumentsResponse response = new TradableInstrumentsResponse(list);

		assertEquals(1, response.instruments().size());
		TradableInstrument first = response.instruments().getFirst();
		assertEquals("NIFTY", first.symbol());
		assertEquals(65, first.lotSize());
		assertEquals("NSE:NIFTY50", first.underlyingKey());
	}
}

package com.sgalgodesk.desk.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.config.InstrumentsProperties.InstrumentDefault;

class InstrumentsPropertiesTest {

	@Test
	void parsesCommaSeparatedTradable() {
		InstrumentsProperties props = new InstrumentsProperties();
		props.setTradable("NIFTY, BANKNIFTY ,finNIFTY");
		assertEquals(List.of("NIFTY", "BANKNIFTY", "FINNIFTY"), props.tradableSymbols());
	}

	@Test
	void lotSizeFallbackFromDefaults() {
		InstrumentsProperties props = new InstrumentsProperties();
		InstrumentDefault nifty = new InstrumentDefault();
		nifty.setLotSize(65);
		props.setDefaults(Map.of("NIFTY", nifty));
		assertEquals(65, props.configuredLotSize("nifty"));
		assertEquals(65, props.lotSizeFallback("NIFTY", 75));
		assertNull(props.configuredLotSize("BANKNIFTY"));
		assertEquals(75, props.lotSizeFallback("BANKNIFTY", 75));
	}

	@Test
	void underlyingKeyMapping() {
		assertEquals("NSE:NIFTY50", InstrumentsProperties.underlyingKeyForSymbol("NIFTY"));
		assertEquals("NSE:NIFTYBANK", InstrumentsProperties.underlyingKeyForSymbol("BANKNIFTY"));
		assertEquals("NSE:FINNIFTY", InstrumentsProperties.underlyingKeyForSymbol("FINNIFTY"));
		assertEquals("BSE:SENSEX", InstrumentsProperties.underlyingKeyForSymbol("SENSEX"));
	}

	@Test
	void emptyTradableYieldsEmptyList() {
		InstrumentsProperties props = new InstrumentsProperties();
		props.setTradable("  ");
		assertTrue(props.tradableSymbols().isEmpty());
		props.setDefaults(new LinkedHashMap<>());
		assertNull(props.configuredLotSize("NIFTY"));
	}
}

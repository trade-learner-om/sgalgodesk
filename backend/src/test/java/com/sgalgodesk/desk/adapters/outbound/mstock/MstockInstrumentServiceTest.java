package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MstockInstrumentServiceTest {

	@Test
	void niftyDoesNotMatchFinniftyOrBanknifty() {
		assertTrue(MstockInstrumentService.matchesUnderlying("NIFTY", "NIFTY", "NIFTY20NOV19500CE"));
		assertTrue(MstockInstrumentService.matchesUnderlying("NIFTY", null, "NIFTY20NOV19500CE"));
		assertFalse(MstockInstrumentService.matchesUnderlying("NIFTY", "FINNIFTY", "FINNIFTY20NOV19450PE"));
		assertFalse(MstockInstrumentService.matchesUnderlying("NIFTY", "BANKNIFTY", "BANKNIFTY20NOV52000CE"));
		assertFalse(MstockInstrumentService.matchesUnderlying("NIFTY", "MIDCPNIFTY", "MIDCPNIFTY20NOV12000CE"));
		assertFalse(MstockInstrumentService.matchesUnderlying("NIFTY", null, "FINNIFTY20NOV19450PE"));
	}

	@Test
	void finniftyMatchesOnlyFinnifty() {
		assertTrue(MstockInstrumentService.matchesUnderlying(
				"FINNIFTY", "FINNIFTY", "FINNIFTY20NOV19450PE"));
		assertFalse(MstockInstrumentService.matchesUnderlying(
				"FINNIFTY", "NIFTY", "NIFTY20NOV19500CE"));
	}

	@Test
	void normalizeMapsNifty50ToNifty() {
		assertEquals("NIFTY", MstockInstrumentService.normalizeUnderlyingSymbol("NIFTY"));
		assertEquals("NIFTY", MstockInstrumentService.normalizeUnderlyingSymbol("NIFTY50"));
		assertEquals("NIFTY", MstockInstrumentService.normalizeUnderlyingSymbol("NSE:NIFTY50"));
		assertEquals("FINNIFTY", MstockInstrumentService.normalizeUnderlyingSymbol("FINNIFTY"));
		assertEquals("BANKNIFTY", MstockInstrumentService.normalizeUnderlyingSymbol("BANKNIFTY"));
	}
}

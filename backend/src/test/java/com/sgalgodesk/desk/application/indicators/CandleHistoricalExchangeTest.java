package com.sgalgodesk.desk.application.indicators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockApiClient;

class CandleHistoricalExchangeTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Test
	void nfoPrimaryIsNfo_alternateIsNse() {
		assertEquals("NFO", CandleService.historicalExchangePrimary("NFO:NIFTY01DEC2525500CE"));
		assertEquals("NSE", CandleService.historicalExchangeAlternate("NFO:NIFTY01DEC2525500CE"));
		assertEquals("NFO", CandleService.historicalExchangePrimary("NFO:12345"));
	}

	@Test
	void bfoPrimaryIsBfo_alternateIsBse() {
		assertEquals("BFO", CandleService.historicalExchangePrimary("BFO:SENSEX..."));
		assertEquals("BSE", CandleService.historicalExchangeAlternate("BFO:SENSEX..."));
	}

	@Test
	void nsePrimaryStaysNse_alternateNfo() {
		assertEquals("NSE", CandleService.historicalExchangePrimary("NSE:NIFTY50"));
		assertEquals("NFO", CandleService.historicalExchangeAlternate("NSE:NIFTY50"));
	}

	@Test
	void keyExchangePrefix() {
		assertEquals("NFO", CandleService.keyExchangePrefix("NFO:abc"));
		assertEquals("NSE", CandleService.keyExchangePrefix("no-colon"));
	}

	@Test
	void intradayExchangeCodes() {
		assertEquals("1", MstockApiClient.intradayExchangeCode("NSE"));
		assertEquals("2", MstockApiClient.intradayExchangeCode("NFO"));
		assertEquals("4", MstockApiClient.intradayExchangeCode("BSE"));
		assertEquals("5", MstockApiClient.intradayExchangeCode("BFO"));
		assertEquals("1", MstockApiClient.intradayExchangeCode(null));
	}

	@Test
	void intersectsTodaySession_trueForTodayWindow() {
		LocalDate today = LocalDate.now(IST);
		Instant from = today.atTime(LocalTime.of(9, 15)).atZone(IST).toInstant();
		Instant to = Instant.now();
		assertTrue(CandleService.intersectsTodaySession(from, to));
	}

	@Test
	void intersectsTodaySession_falseForPriorDayOnly() {
		LocalDate prior = LocalDate.now(IST).minusDays(1);
		Instant from = prior.atTime(LocalTime.of(9, 15)).atZone(IST).toInstant();
		Instant to = prior.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
		assertFalse(CandleService.intersectsTodaySession(from, to));
	}

	@Test
	void spansPriorSessionDay() {
		LocalDate today = LocalDate.now(IST);
		Instant todayOpen = today.atTime(LocalTime.of(9, 15)).atZone(IST).toInstant();
		assertFalse(CandleService.spansPriorSessionDay(todayOpen));
		assertTrue(CandleService.spansPriorSessionDay(todayOpen.minusSeconds(1)));
	}
}

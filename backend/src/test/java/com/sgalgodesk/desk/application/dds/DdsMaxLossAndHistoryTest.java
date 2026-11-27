package com.sgalgodesk.desk.application.dds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.dds.DdsDtos.DdsSettings;
import com.sgalgodesk.desk.application.dds.DdsDtos.TradeDto;
import com.sgalgodesk.desk.application.dds.DdsHistoryGrouping.Page;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

class DdsMaxLossAndHistoryTest {

	@Test
	void pointsMaxLoss() {
		DdsSettings s = new DdsSettings(25, "POINTS", 40, "15:00", 2);
		assertFalse(DdsStrategyService.maxLossHit(s, -100, -39));
		assertTrue(DdsStrategyService.maxLossHit(s, -100, -40));
		assertTrue(DdsStrategyService.maxLossHit(s, -10, -50));
	}

	@Test
	void moneyMaxLossMultipliesLots() {
		DdsSettings s = new DdsSettings(25, "MONEY", 500, "15:00", 2);
		// budget = 500 * 2 = 1000
		assertFalse(DdsStrategyService.maxLossHit(s, -999, -100));
		assertTrue(DdsStrategyService.maxLossHit(s, -1000, -10));
	}

	@Test
	void historyCursorPagesByDayDescending() {
		UUID sid = UUID.randomUUID();
		UUID rid = UUID.randomUUID();
		UUID uid = UUID.randomUUID();
		DdsTrade d1a = trade(sid, rid, uid, Instant.parse("2026-11-27T04:00:00Z"), 10);
		DdsTrade d1b = trade(sid, rid, uid, Instant.parse("2026-11-27T05:00:00Z"), -5);
		DdsTrade d2 = trade(sid, rid, uid, Instant.parse("2026-11-26T04:00:00Z"), 3);
		DdsTrade d3 = trade(sid, rid, uid, Instant.parse("2026-11-25T04:00:00Z"), 1);

		Page page1 = DdsHistoryGrouping.page(
				List.of(d1a, d1b, d2, d3),
				LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30),
				null,
				2,
				this::dto);
		assertEquals(2, page1.groups().size());
		assertEquals(LocalDate.of(2026, 11, 27), page1.groups().get(0).date());
		assertEquals(LocalDate.of(2026, 11, 26), page1.groups().get(1).date());
		assertEquals(5.0, page1.groups().get(0).dayPnl(), 0.01);
		assertTrue(page1.hasMore());
		assertEquals("2026-11-26", page1.nextCursor());

		Page page2 = DdsHistoryGrouping.page(
				List.of(d1a, d1b, d2, d3),
				LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30),
				LocalDate.parse(page1.nextCursor()),
				2,
				this::dto);
		assertEquals(1, page2.groups().size());
		assertEquals(LocalDate.of(2026, 11, 25), page2.groups().get(0).date());
		assertFalse(page2.hasMore());
		assertNull(page2.nextCursor());
	}

	private TradeDto dto(DdsTrade t) {
		return new TradeDto(
				t.id(), t.strategyId(), t.runId(), t.legRole(), t.instrumentKey(), t.tradingSymbol(), t.side(),
				t.quantity(), t.executionMode(), t.brokerOrderId(), t.entryPrice(), t.exitPrice(), t.pnl(),
				t.triggerReason(), t.status(), t.deltaAtEntry(), t.lotSize(), t.strike(), t.expiry(),
				t.optionType(), t.executedAt(), t.closedAt());
	}

	private static DdsTrade trade(UUID sid, UUID rid, UUID uid, Instant at, double pnl) {
		return new DdsTrade(
				UUID.randomUUID(), sid, rid, uid, "CE", "NFO:1", "CE-24500", "SELL", 65, "PAPER", null,
				100, 90.0, pnl, "ENTRY", "CLOSED", 0.25, 65, 24500.0, "2026-12-02", "CE", at, at);
	}
}

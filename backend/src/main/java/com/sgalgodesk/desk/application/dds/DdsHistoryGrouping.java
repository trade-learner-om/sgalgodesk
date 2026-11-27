package com.sgalgodesk.desk.application.dds;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sgalgodesk.desk.application.dds.DdsDtos.HistoryDayGroup;
import com.sgalgodesk.desk.application.dds.DdsDtos.TradeDto;
import com.sgalgodesk.desk.domain.dds.DdsTrade;

/**
 * Groups trades by IST calendar day and applies classic day-cursor pagination.
 */
public final class DdsHistoryGrouping {

	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	public record Page(List<HistoryDayGroup> groups, String nextCursor, boolean hasMore) {
	}

	private DdsHistoryGrouping() {
	}

	public static Page page(
			List<DdsTrade> trades,
			LocalDate from,
			LocalDate to,
			LocalDate cursorExclusiveUpper,
			int limitDays,
			java.util.function.Function<DdsTrade, TradeDto> toDto) {
		int limit = Math.max(1, limitDays);
		Map<LocalDate, List<DdsTrade>> byDay = new LinkedHashMap<>();
		for (DdsTrade trade : trades) {
			if (trade == null || trade.executedAt() == null) {
				continue;
			}
			LocalDate day = trade.executedAt().atZone(IST).toLocalDate();
			if (from != null && day.isBefore(from)) {
				continue;
			}
			if (to != null && day.isAfter(to)) {
				continue;
			}
			if (cursorExclusiveUpper != null && !day.isBefore(cursorExclusiveUpper)) {
				continue;
			}
			byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(trade);
		}
		List<LocalDate> days = byDay.keySet().stream()
				.sorted(Comparator.reverseOrder())
				.toList();
		boolean hasMore = days.size() > limit;
		List<LocalDate> pageDays = days.stream().limit(limit).toList();
		List<HistoryDayGroup> groups = new ArrayList<>();
		for (LocalDate day : pageDays) {
			List<DdsTrade> dayTrades = byDay.get(day).stream()
					.sorted(Comparator.comparing(DdsTrade::executedAt))
					.toList();
			List<TradeDto> dtos = dayTrades.stream().map(toDto).filter(Objects::nonNull).toList();
			double dayPnl = dayTrades.stream().mapToDouble(DdsTrade::pnl).sum();
			groups.add(new HistoryDayGroup(day, dtos, round2(dayPnl)));
		}
		String next = hasMore && !pageDays.isEmpty() ? pageDays.getLast().toString() : null;
		return new Page(groups, next, hasMore);
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}

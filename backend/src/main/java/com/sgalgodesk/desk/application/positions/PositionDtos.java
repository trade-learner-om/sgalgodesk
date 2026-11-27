package com.sgalgodesk.desk.application.positions;

import java.time.Instant;
import java.util.List;

public final class PositionDtos {

	private PositionDtos() {
	}

	public record PositionItem(
			String instrumentKey,
			String tradingSymbol,
			String exchange,
			String product,
			int quantity,
			double averagePrice,
			double lastPrice,
			double pnl,
			double realised,
			double unrealised,
			String underlyingKey,
			String underlyingSymbol,
			String instrumentType,
			Double strike,
			String expiry,
			Integer lotSize,
			boolean open,
			/** Earliest fill time for this open position when known (from trade book). */
			Instant entryAt) {
	}

	public record PositionGroup(
			String underlyingKey,
			String underlyingSymbol,
			List<PositionItem> positions) {
	}

	public record PositionsResponse(
			List<PositionGroup> openGroups,
			List<PositionGroup> closedGroups,
			int brokerRowCount,
			int skippedRowCount,
			String brokerUserId,
			String brokerUserName,
			int todayOrderCount,
			int todayNfoOrderCount,
			String source) {
	}
}

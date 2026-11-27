package com.sgalgodesk.desk.application.optionchain;

import java.util.List;

public final class OptionChainDtos {

	private OptionChainDtos() {
	}

	public record OptionSide(
			String instrumentKey,
			String tradingSymbol,
			Double ltp,
			Double closePrice,
			Double ltpChangePct,
			Double oi,
			Double prevOi,
			Double volume,
			Double iv,
			Double delta,
			Double theta,
			Double gamma,
			Double vega,
			Double pop,
			Double bid,
			Double ask,
			Integer lotSize
	) {
	}

	public record OptionStrikeRow(
			double strike,
			OptionSide call,
			OptionSide put,
			Double pcr
	) {
	}

	public record OptionChainResponse(
			String underlyingKey,
			String underlyingSymbol,
			Double underlyingSpot,
			String expiryDate,
			Double pcr,
			Double maxPain,
			Integer lotSize,
			String futuresKey,
			String futuresSymbol,
			List<OptionStrikeRow> strikes
	) {
	}
}

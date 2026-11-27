package com.sgalgodesk.desk.application.indicators;

import java.util.List;

public final class IndicatorDtos {

	private IndicatorDtos() {
	}

	public enum Bias {
		LONG,
		SHORT,
		NONE
	}

	public record CandleDto(
			String timestamp,
			double open,
			double high,
			double low,
			double close,
			long volume,
			long openInterest) {
	}

	public record CandlesResponse(List<CandleDto> candles) {
	}

	public record SignalResponse(
			String instrumentKey,
			String timestamp,
			double close,
			double ema9,
			double supertrend,
			String supertrendDirection,
			Bias bias,
			String reason) {
	}
}

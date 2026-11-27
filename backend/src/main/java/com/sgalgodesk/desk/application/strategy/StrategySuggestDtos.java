package com.sgalgodesk.desk.application.strategy;

import java.util.List;

import com.sgalgodesk.desk.application.indicators.IndicatorDtos.Bias;
import com.sgalgodesk.desk.application.indicators.IndicatorDtos.SignalResponse;

import jakarta.validation.constraints.NotBlank;

public final class StrategySuggestDtos {

	private StrategySuggestDtos() {
	}

	public record SuggestRequest(
			@NotBlank String instrumentKey,
			Boolean debitOverride) {
	}

	public record SuggestedLeg(
			String instrumentKey,
			String tradingSymbol,
			String kind,
			String side,
			double strike,
			String expiry,
			int lots,
			Integer lotSize,
			double premium) {
	}

	public record WingTrial(
			int otmSteps,
			double sellStrike,
			double buyStrike,
			double creditPerLot,
			double maxProfitPerLot,
			double maxLossPerLot,
			double riskReward,
			List<SuggestedLeg> legs) {
	}

	public record ExpiryScenario(
			String expiry,
			int daysToExpiry,
			boolean preferred,
			WingTrial bestWing,
			List<WingTrial> allWings) {
	}

	public record SuggestResponse(
			String instrumentKey,
			String underlyingSymbol,
			double spot,
			Bias bias,
			boolean debit,
			String spreadType,
			SignalResponse signal,
			List<ExpiryScenario> scenarios) {
	}
}

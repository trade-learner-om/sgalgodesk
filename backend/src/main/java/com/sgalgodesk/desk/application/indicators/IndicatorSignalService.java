package com.sgalgodesk.desk.application.indicators;

import com.sgalgodesk.desk.domain.indicators.Candle;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import com.sgalgodesk.desk.application.indicators.IndicatorDtos.Bias;
import com.sgalgodesk.desk.application.indicators.IndicatorDtos.SignalResponse;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class IndicatorSignalService {

	private static final int EMA_LENGTH = 9;
	private static final int ATR_PERIOD = 10;
	private static final double ST_FACTOR = 3.5;

	private final CandleService candleService;

	public Mono<SignalResponse> signal(UUID userId, String instrumentKey) {
		return candleService.fetchMerged75m(userId, instrumentKey)
				.map(candles -> computeSignal(instrumentKey, candles));
	}

	SignalResponse computeSignal(String instrumentKey, List<Candle> candles) {
		if (candles.size() < Math.max(EMA_LENGTH, ATR_PERIOD) + 2) {
			throw new ValidationFailed(
					"Not enough 75m candles for indicators (need warmup)");
		}
		// Prefer last fully closed bar: drop the most recent if we want; use last available.
		int idx = candles.size() - 1;
		List<EmaPoint> ema = TradingViewIndicatorUtil.calculateEma(candles, EMA_LENGTH);
		List<SupertrendPoint> st = TradingViewIndicatorUtil.calculateSupertrend(candles, ST_FACTOR, ATR_PERIOD);
		Candle bar = candles.get(idx);
		EmaPoint emaPoint = ema.get(idx);
		SupertrendPoint stPoint = st.get(idx);
		if (Double.isNaN(emaPoint.value()) || Double.isNaN(stPoint.supertrend())) {
			throw new ValidationFailed("Indicators still warming up");
		}

		Bias bias = Bias.NONE;
		String reason;
		if (stPoint.direction() == TrendDirection.DOWN && bar.close() > emaPoint.value()) {
			bias = Bias.SHORT;
			reason = "Supertrend DOWN and close above EMA(9) → suggest Bear Call credit";
		}
		else if (stPoint.direction() == TrendDirection.UP && bar.close() < emaPoint.value()) {
			bias = Bias.LONG;
			reason = "Supertrend UP and close below EMA(9) → suggest Bull Put credit";
		}
		else {
			reason = "No entry: need ST direction vs EMA cross rule";
		}

		return new SignalResponse(
				instrumentKey,
				bar.timestamp().toString(),
				bar.close(),
				emaPoint.value(),
				stPoint.supertrend(),
				stPoint.direction().name(),
				bias,
				reason);
	}
}

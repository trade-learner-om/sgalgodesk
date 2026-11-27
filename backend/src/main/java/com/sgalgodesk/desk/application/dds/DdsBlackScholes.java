package com.sgalgodesk.desk.application.dds;

/**
 * Black-Scholes helpers for delta / IV (mirrors frontend payoff/blackScholes.ts).
 */
public final class DdsBlackScholes {

	private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);

	private DdsBlackScholes() {
	}

	public enum OptionKind {
		CE, PE
	}

	private static double normPdf(double x) {
		return Math.exp(-0.5 * x * x) / SQRT_2PI;
	}

	private static double normCdf(double x) {
		double t = 1 / (1 + 0.2316419 * Math.abs(x));
		double d = 0.3989423 * Math.exp((-x * x) / 2);
		double probability =
				d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
		if (x > 0) {
			probability = 1 - probability;
		}
		return probability;
	}

	private record D1D2(double d1, double d2, double sqrtT) {
	}

	private static D1D2 d1d2(double spot, double strike, double timeYears, double volatility, double rate) {
		if (timeYears <= 0 || volatility <= 0 || spot <= 0 || strike <= 0) {
			return null;
		}
		double sqrtT = Math.sqrt(timeYears);
		double d1 = (Math.log(spot / strike) + (rate + 0.5 * volatility * volatility) * timeYears)
				/ (volatility * sqrtT);
		return new D1D2(d1, d1 - volatility * sqrtT, sqrtT);
	}

	public static double price(
			double spot, double strike, double timeYears, double volatility, OptionKind kind, double rate) {
		D1D2 parts = d1d2(spot, strike, timeYears, volatility, rate);
		if (parts == null) {
			return kind == OptionKind.CE ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0);
		}
		double discount = Math.exp(-rate * timeYears);
		if (kind == OptionKind.CE) {
			return spot * normCdf(parts.d1) - strike * discount * normCdf(parts.d2);
		}
		return strike * discount * normCdf(-parts.d2) - spot * normCdf(-parts.d1);
	}

	public static double delta(
			double spot, double strike, double timeYears, double volatility, OptionKind kind, double rate) {
		D1D2 parts = d1d2(spot, strike, timeYears, volatility, rate);
		if (parts == null) {
			if (kind == OptionKind.CE) {
				return spot > strike ? 1 : 0;
			}
			return spot < strike ? -1 : 0;
		}
		return kind == OptionKind.CE ? normCdf(parts.d1) : normCdf(parts.d1) - 1;
	}

	/** Binary-search implied vol from market premium (fractional, e.g. 0.15 = 15%). */
	public static Double impliedVolatility(
			double marketPrice, double spot, double strike, double timeYears, OptionKind kind, double rate) {
		if (marketPrice <= 0 || spot <= 0 || strike <= 0 || timeYears <= 0 || !Double.isFinite(marketPrice)) {
			return null;
		}
		double lo = 0.01;
		double hi = 3;
		double intrinsic = kind == OptionKind.CE ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0);
		if (marketPrice < intrinsic * 0.98) {
			return null;
		}
		for (int i = 0; i < 40; i++) {
			double mid = (lo + hi) / 2;
			double midPrice = price(spot, strike, timeYears, mid, kind, rate);
			if (midPrice > marketPrice) {
				hi = mid;
			}
			else {
				lo = mid;
			}
		}
		double iv = (lo + hi) / 2;
		return Double.isFinite(iv) && iv > 0 ? iv : null;
	}
}

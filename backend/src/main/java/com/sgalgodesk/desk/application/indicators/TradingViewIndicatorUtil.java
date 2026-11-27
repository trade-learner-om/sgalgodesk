package com.sgalgodesk.desk.application.indicators;

import com.sgalgodesk.desk.domain.indicators.Candle;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Production-ready Pine Script v5/v6-parity {@code ta.ema} and {@code ta.supertrend}
 * calculations, plus an adapter for broker OHLCV candle arrays.
 *
 * <p>All math uses IEEE 754 {@code double}. Warmup recommendation: feed at least
 * {@code 3×–5×} the indicator period (e.g. 30–50 bars for ATR/EMA length 10) so
 * early NaN / unstable seed bars are behind the signal bar you trade on.
 *
 * <p>Zero external TA libraries — JDK only.
 */
public final class TradingViewIndicatorUtil {

	private TradingViewIndicatorUtil() {
	}

	/**
	 * Maps candle rows {@code [timestamp, open, high, low, close, volume, oi]}
	 * into {@link Candle} records, sorted ascending by timestamp.
	 *
	 * @throws IllegalArgumentException if {@code rawCandles} is null
	 */
	public static List<Candle> fromCandles(List<List<Object>> rawCandles) {
		if (rawCandles == null) {
			throw new IllegalArgumentException("rawCandles must not be null");
		}
		List<Candle> out = new ArrayList<>(rawCandles.size());
		for (List<Object> row : rawCandles) {
			if (row == null || row.size() < 5) {
				continue;
			}
			try {
				Instant ts = parseTimestamp(row.get(0));
				double open = toDouble(row.get(1));
				double high = toDouble(row.get(2));
				double low = toDouble(row.get(3));
				double close = toDouble(row.get(4));
				long volume = row.size() > 5 ? toLong(row.get(5)) : 0L;
				long oi = row.size() > 6 ? toLong(row.get(6)) : 0L;
				out.add(new Candle(ts, open, high, low, close, volume, oi));
			}
			catch (RuntimeException ignored) {
				// skip malformed row; do not fail the whole batch
			}
		}
		out.sort(Comparator.comparing(Candle::timestamp));
		return List.copyOf(out);
	}

	/**
	 * Pine {@code ta.ema(source, length)} over candle closes.
	 *
	 * <p>Indices {@code 0 .. min(length, n) - 2} are {@link Double#NaN}; at
	 * {@code length - 1} the value is the SMA seed; thereafter
	 * {@code ema = alpha * close + (1 - alpha) * prev} with
	 * {@code alpha = 2 / (length + 1)}.
	 */
	public static List<EmaPoint> calculateEma(List<Candle> candles, int length) {
		if (candles == null) {
			throw new IllegalArgumentException("candles must not be null");
		}
		if (length <= 0) {
			throw new IllegalArgumentException("length must be > 0");
		}
		if (candles.isEmpty()) {
			return List.of();
		}
		double[] closes = new double[candles.size()];
		for (int i = 0; i < candles.size(); i++) {
			closes[i] = candles.get(i).close();
		}
		double[] ema = calculateEmaValues(closes, length);
		List<EmaPoint> points = new ArrayList<>(candles.size());
		for (int i = 0; i < candles.size(); i++) {
			points.add(new EmaPoint(candles.get(i).timestamp(), ema[i]));
		}
		return List.copyOf(points);
	}

	/**
	 * Same EMA math as {@link #calculateEma(List, int)} over a raw close series.
	 */
	public static double[] calculateEmaValues(double[] closes, int length) {
		if (closes == null) {
			throw new IllegalArgumentException("closes must not be null");
		}
		if (length <= 0) {
			throw new IllegalArgumentException("length must be > 0");
		}
		int n = closes.length;
		double[] ema = new double[n];
		int nanUntil = Math.min(length - 1, n);
		for (int i = 0; i < nanUntil; i++) {
			ema[i] = Double.NaN;
		}
		if (n < length) {
			return ema;
		}
		double sum = 0.0;
		for (int i = 0; i < length; i++) {
			sum += closes[i];
		}
		ema[length - 1] = sum / length;
		double alpha = 2.0 / (length + 1.0);
		for (int i = length; i < n; i++) {
			ema[i] = alpha * closes[i] + (1.0 - alpha) * ema[i - 1];
		}
		return ema;
	}

	/**
	 * Pine {@code ta.supertrend(factor, atrPeriod)} parity:
	 * <ol>
	 *   <li>{@code ta.tr(true)} true range</li>
	 *   <li>{@code ta.atr(atrPeriod)} Wilder RMA (not SMA/EMA)</li>
	 *   <li>Basic/final bands and direction flip vs previous Supertrend</li>
	 * </ol>
	 * Prefer {@code 3×–5× atrPeriod} bars of history before reading the latest direction.
	 */
	public static List<SupertrendPoint> calculateSupertrend(List<Candle> candles, double factor, int atrPeriod) {
		if (candles == null) {
			throw new IllegalArgumentException("candles must not be null");
		}
		if (factor <= 0) {
			throw new IllegalArgumentException("factor must be > 0");
		}
		if (atrPeriod <= 0) {
			throw new IllegalArgumentException("atrPeriod must be > 0");
		}
		if (candles.isEmpty()) {
			return List.of();
		}

		int n = candles.size();
		double[] high = new double[n];
		double[] low = new double[n];
		double[] close = new double[n];
		for (int i = 0; i < n; i++) {
			Candle c = candles.get(i);
			high[i] = c.high();
			low[i] = c.low();
			close[i] = c.close();
		}

		double[] tr = new double[n];
		tr[0] = high[0] - low[0];
		for (int i = 1; i < n; i++) {
			tr[i] = Math.max(high[i] - low[i],
					Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
		}

		double[] atr = new double[n];
		for (int i = 0; i < Math.min(atrPeriod - 1, n); i++) {
			atr[i] = Double.NaN;
		}
		if (n >= atrPeriod) {
			double sum = 0.0;
			for (int i = 0; i < atrPeriod; i++) {
				sum += tr[i];
			}
			atr[atrPeriod - 1] = sum / atrPeriod;
			for (int i = atrPeriod; i < n; i++) {
				atr[i] = (atr[i - 1] * (atrPeriod - 1) + tr[i]) / atrPeriod;
			}
		}

		double[] upperBand = new double[n];
		double[] lowerBand = new double[n];
		double[] supertrend = new double[n];
		TrendDirection[] direction = new TrendDirection[n];
		List<SupertrendPoint> points = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			Instant ts = candles.get(i).timestamp();
			if (Double.isNaN(atr[i])) {
				upperBand[i] = Double.NaN;
				lowerBand[i] = Double.NaN;
				supertrend[i] = Double.NaN;
				direction[i] = TrendDirection.DOWN;
				points.add(new SupertrendPoint(ts, Double.NaN, TrendDirection.DOWN,
						Double.NaN, Double.NaN, Double.NaN));
				continue;
			}

			double hl2 = (high[i] + low[i]) / 2.0;
			double basicUpper = hl2 + factor * atr[i];
			double basicLower = hl2 - factor * atr[i];
			double prevUpper = i > 0 ? upperBand[i - 1] : Double.NaN;
			double prevLower = i > 0 ? lowerBand[i - 1] : Double.NaN;
			double prevClose = i > 0 ? close[i - 1] : Double.NaN;

			upperBand[i] = (Double.isNaN(prevUpper) || basicUpper < prevUpper || prevClose > prevUpper)
					? basicUpper
					: prevUpper;
			lowerBand[i] = (Double.isNaN(prevLower) || basicLower > prevLower || prevClose < prevLower)
					? basicLower
					: prevLower;

			TrendDirection dir;
			if (i == 0 || Double.isNaN(atr[i - 1])) {
				dir = TrendDirection.DOWN;
			} else if (Double.compare(supertrend[i - 1], prevUpper) == 0) {
				dir = close[i] > upperBand[i] ? TrendDirection.UP : TrendDirection.DOWN;
			} else {
				dir = close[i] < lowerBand[i] ? TrendDirection.DOWN : TrendDirection.UP;
			}
			direction[i] = dir;
			supertrend[i] = dir == TrendDirection.UP ? lowerBand[i] : upperBand[i];

			points.add(new SupertrendPoint(ts, supertrend[i], dir, upperBand[i], lowerBand[i], atr[i]));
		}

		return List.copyOf(points);
	}

	private static final DateTimeFormatter FLEX_OFFSET = new DateTimeFormatterBuilder()
			.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
			.optionalStart()
			.appendOffset("+HH:MM", "+00:00")
			.optionalEnd()
			.optionalStart()
			.appendOffset("+HHMM", "+0000")
			.optionalEnd()
			.optionalStart()
			.appendOffset("+HH", "+00")
			.optionalEnd()
			.toFormatter();

	private static Instant parseTimestamp(Object raw) {
		if (raw == null) {
			throw new IllegalArgumentException("candle timestamp is null");
		}
		if (raw instanceof Instant instant) {
			return instant;
		}
		if (raw instanceof Number number) {
			long v = number.longValue();
			// seconds vs millis heuristic
			return v > 10_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
		}
		String s = raw.toString().trim();
		try {
			return OffsetDateTime.parse(s, FLEX_OFFSET).toInstant();
		}
		catch (RuntimeException ignored) {
			// fall through
		}
		try {
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (RuntimeException ignored) {
			// fall through
		}
		// "yyyy-MM-dd HH:mm[:ss]" without offset — assume IST (+05:30)
		// MStock intraday Type B uses "2025-04-04 15:27"
		String normalized = s.replace(' ', 'T');
		try {
			LocalDateTime ldt = LocalDateTime.parse(normalized);
			return ldt.toInstant(ZoneOffset.ofHoursMinutes(5, 30));
		}
		catch (RuntimeException ignored) {
			// fall through
		}
		try {
			LocalDateTime ldt = LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
			return ldt.toInstant(ZoneOffset.ofHoursMinutes(5, 30));
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("Unparseable candle timestamp: " + s, ex);
		}
	}

	private static double toDouble(Object obj) {
		if (obj == null) {
			return Double.NaN;
		}
		if (obj instanceof Number number) {
			return number.doubleValue();
		}
		return Double.parseDouble(obj.toString());
	}

	private static long toLong(Object obj) {
		if (obj == null) {
			return 0L;
		}
		if (obj instanceof Number number) {
			return number.longValue();
		}
		return (long) Double.parseDouble(obj.toString());
	}
}

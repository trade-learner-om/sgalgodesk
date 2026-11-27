package com.sgalgodesk.desk.application.positions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionGroup;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PositionBookMapper {

	public static final String SOURCE_MSTOCK_POSITIONS = "mstock-positions";

	private final MstockInstrumentService instrumentService;

	public record BrokerProfile(String userId, String userName) {
	}

	public record TradeSeedResult(
			Map<String, PositionItem> byKey,
			int tradeRows,
			int skipped,
			int nfoTrades) {
	}

	public TradeSeedResult seedFromMstockPositions(JsonNode positionsJson) {
		JsonNode data = positionsJson.path("data");
		if (!data.isArray() && positionsJson.isArray()) {
			data = positionsJson;
		}
		Map<String, PositionItem> byKey = new LinkedHashMap<>();
		int rows = data.isArray() ? data.size() : 0;
		int skipped = 0;
		int nfo = 0;
		if (!data.isArray()) {
			return new TradeSeedResult(byKey, 0, 0, 0);
		}
		for (JsonNode row : data) {
			String symbolToken = firstText(row, "symboltoken", "symbol_token", "instrument_token", "instrument_key");
			String symbol = firstText(row, "symbolname", "tradingsymbol", "trading_symbol");
			String exchange = firstText(row, "exchange");
			if (exchange == null || exchange.isBlank()) {
				exchange = "NFO";
			}
			if (symbolToken == null && symbol == null) {
				skipped++;
				continue;
			}
			String instrumentType = firstText(row, "instrumenttype", "instrument_type", "optiontype");
			if (instrumentType == null || instrumentType.isBlank()) {
				instrumentType = inferType(symbol);
			}
			// Positions API has no LTP — key must be token-based so Type B quote / WS can resolve it.
			String instrumentKey = symbolToken != null
					? instrumentService.ensureFeedKeyForToken(exchange, symbolToken, instrumentType)
					: exchange + ":" + symbol;
			if (instrumentKey == null || instrumentKey.isBlank()) {
				instrumentKey = exchange + ":" + (symbol != null ? symbol : symbolToken);
			}
			String productRaw = firstText(row, "producttype", "product");
			String product = mapMstockProduct(productRaw);
			int quantity = (int) Math.round(number(row, "netqty", number(row, "quantity", 0)));
			double avg = number(row, "avgnetprice", number(row, "buyavgprice", number(row, "average_price", 0)));
			double ltp = number(row, "ltp", number(row, "lastprice", number(row, "last_price", 0)));
			double realised = number(row, "realizedprofit", number(row, "realised", 0));
			Integer lotSize = (int) Math.round(number(row, "lotsize", number(row, "boardlotsize", 0)));
			if (lotSize != null && lotSize == 0) {
				lotSize = null;
			}
			Double strike = numberOrNull(row, "strikeprice", "strike");
			String expiry = firstText(row, "expirydate", "expiry");
			String optionType = firstText(row, "optiontype");
			if (optionType != null && !optionType.isBlank()) {
				instrumentType = optionType;
			}
			PositionItem item = enrich(
					instrumentKey,
					symbol,
					exchange,
					product,
					quantity,
					avg,
					realised,
					ltp,
					symbolToken,
					instrumentType,
					strike,
					expiry,
					lotSize);
			byKey.put(bookKey(item.instrumentKey(), product), item);
			if (exchange.toUpperCase(Locale.ROOT).contains("FO")
					|| "OPTIDX".equalsIgnoreCase(instrumentType)
					|| (symbol != null && symbol.toUpperCase(Locale.ROOT).contains("NIFTY"))) {
				nfo++;
			}
		}
		return new TradeSeedResult(byKey, rows, skipped, nfo);
	}

	/** Quote-exchange for Type B LTP (positions often report NSE for NFO options). */
	public static String quoteExchangeFor(PositionItem item) {
		if (item == null) {
			return "NSE";
		}
		String key = item.instrumentKey() != null ? item.instrumentKey() : "";
		if (key.startsWith("BFO:") || key.startsWith("BSE:")) {
			return key.startsWith("BFO:") ? "BFO" : "BSE";
		}
		if (key.startsWith("NFO:")) {
			// Type B sample uses NSE + symboltoken for OPTIDX
			return "NSE";
		}
		String ex = item.exchange();
		return ex != null && !ex.isBlank() ? ex : "NSE";
	}

	public static String tokenFromKey(String instrumentKey) {
		if (instrumentKey == null || !instrumentKey.contains(":")) {
			return instrumentKey;
		}
		return instrumentKey.substring(instrumentKey.indexOf(':') + 1);
	}

	static boolean isBareNumericToken(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String t = value.trim();
		if (t.isEmpty()) {
			return false;
		}
		for (int i = 0; i < t.length(); i++) {
			if (!Character.isDigit(t.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Map broker {@code instrument_token} / bare digits to a feed key ({@code NFO:…}).
	 * Prefers script-master via {@link MstockInstrumentService#ensureFeedKeyForToken}.
	 */
	String resolveTradeInstrumentKey(String rawKey, String exchange, String tradingSymbol) {
		if (rawKey == null || rawKey.isBlank()) {
			return rawKey;
		}
		String trimmed = rawKey.trim();
		String type = inferType(tradingSymbol);
		String token = trimmed.contains(":") ? tokenFromKey(trimmed) : trimmed;
		if (instrumentService != null && isBareNumericToken(token)) {
			String feed = instrumentService.ensureFeedKeyForToken(exchange, token, type);
			if (feed != null && !feed.isBlank()) {
				return feed;
			}
		}
		if (!trimmed.contains(":") && isBareNumericToken(trimmed)) {
			return syntheticFoKey(exchange, type, trimmed);
		}
		return trimmed;
	}

	private static String syntheticFoKey(String exchange, String instrumentType, String token) {
		String ex = exchange == null ? "" : exchange.toUpperCase(Locale.ROOT);
		String t = instrumentType == null ? "" : instrumentType.toUpperCase(Locale.ROOT);
		boolean optOrFut = t.contains("OPT") || t.contains("CE") || t.contains("PE") || t.contains("FUT");
		if (ex.contains("BFO") || (ex.contains("BSE") && optOrFut)) {
			return "BFO:" + token;
		}
		if (optOrFut || ex.contains("NFO") || ex.contains("FO") || ex.contains("NSE")) {
			return "NFO:" + token;
		}
		if (ex.contains("BSE")) {
			return "BSE:" + token;
		}
		return "NFO:" + token;
	}

	public PositionItem withLastPrice(PositionItem item, double lastPrice) {
		if (item == null) {
			return null;
		}
		double unrealised = 0;
		if (item.quantity() != 0 && lastPrice > 0 && item.averagePrice() > 0) {
			unrealised = item.quantity() * (lastPrice - item.averagePrice());
		}
		double pnl = item.realised() + unrealised;
		return new PositionItem(
				item.instrumentKey(),
				item.tradingSymbol(),
				item.exchange(),
				item.product(),
				item.quantity(),
				item.averagePrice(),
				lastPrice,
				pnl,
				item.realised(),
				unrealised,
				item.underlyingKey(),
				item.underlyingSymbol(),
				item.instrumentType(),
				item.strike(),
				item.expiry(),
				item.lotSize(),
				item.open(),
				item.entryAt());
	}

	/**
	 * Merge open-of-current-net times from the trade book into an existing position seed.
	 * Uses a chronological qty walk (not mere earliest fill).
	 */
	public TradeSeedResult withEntryAts(TradeSeedResult seed, JsonNode tradesJson) {
		if (seed == null || seed.byKey().isEmpty()) {
			return seed;
		}
		Map<String, Instant> opens = openTimesFromTrades(tradesJson);
		if (opens.isEmpty()) {
			return seed;
		}
		Map<String, PositionItem> next = new LinkedHashMap<>();
		for (Map.Entry<String, PositionItem> e : seed.byKey().entrySet()) {
			PositionItem item = e.getValue();
			Instant openAt = resolveFill(opens, e.getKey(), item);
			// Prefer open-of-current-net even when later than an earlier closed-cycle fill.
			if (openAt != null) {
				next.put(e.getKey(), withEntryAt(item, openAt));
			}
			else {
				next.put(e.getKey(), item);
			}
		}
		return new TradeSeedResult(next, seed.tradeRows(), seed.skipped(), seed.nfoTrades());
	}

	private static Instant resolveFill(Map<String, Instant> fills, String bookKey, PositionItem item) {
		Instant direct = fills.get(bookKey);
		if (direct != null) {
			return direct;
		}
		String product = item.product() != null ? item.product() : "D";
		String key = item.instrumentKey();
		if (key == null) {
			return null;
		}
		Instant byKey = fills.get(bookKey(key, product));
		if (byKey != null) {
			return byKey;
		}
		String token = tokenFromKey(key);
		Instant byToken = fills.get(bookKey(token, product));
		if (byToken != null) {
			return byToken;
		}
		for (Map.Entry<String, Instant> f : fills.entrySet()) {
			String fk = f.getKey();
			if (fk.startsWith(key + "|") || fk.startsWith(token + "|") || fk.equals(key) || fk.equals(token)) {
				return f.getValue();
			}
		}
		return null;
	}

	public static PositionItem withEntryAt(PositionItem item, Instant entryAt) {
		if (item == null) {
			return null;
		}
		return new PositionItem(
				item.instrumentKey(),
				item.tradingSymbol(),
				item.exchange(),
				item.product(),
				item.quantity(),
				item.averagePrice(),
				item.lastPrice(),
				item.pnl(),
				item.realised(),
				item.unrealised(),
				item.underlyingKey(),
				item.underlyingSymbol(),
				item.instrumentType(),
				item.strike(),
				item.expiry(),
				item.lotSize(),
				item.open(),
				entryAt);
	}

	/**
	 * Open time of the current continuous net per {@code instrument|product} (and bare-token index).
	 * Walks fills chronologically; open starts when qty leaves 0.
	 */
	Map<String, Instant> openTimesFromTrades(JsonNode tradesJson) {
		Map<String, List<TradeFill>> byKey = new LinkedHashMap<>();
		if (tradesJson == null) {
			return Map.of();
		}
		JsonNode data = tradesJson.path("data");
		if (!data.isArray() && tradesJson.isArray()) {
			data = tradesJson;
		}
		if (!data.isArray()) {
			return Map.of();
		}
		LocalDate fallbackDay = LocalDate.now(IST);
		for (JsonNode row : data) {
			String rawKey = firstText(row, "instrument_token", "instrument_key", "instrumentKey",
					"symboltoken", "symbol_token");
			if (rawKey == null || rawKey.isBlank()) {
				continue;
			}
			String exchange = firstText(row, "exchange");
			String symbol = firstText(row, "trading_symbol", "tradingsymbol");
			String instrumentKey = resolveTradeInstrumentKey(rawKey, exchange, symbol);
			String rawProduct = firstText(row, "product", "producttype");
			String product = mapMstockProduct(rawProduct);
			String side = firstText(row, "transaction_type", "transactiontype", "buy_sell");
			int qty = (int) Math.round(number(row, "quantity", 0));
			if (qty <= 0 || side == null) {
				continue;
			}
			int signed = signedTradeQty(side, qty);
			if (signed == 0) {
				continue;
			}
			LocalDate rowDay = tradeRowDate(row, fallbackDay);
			Instant ts = parseTradeTimestamp(row, rowDay);
			if (ts == null) {
				continue;
			}
			TradeFill fill = new TradeFill(ts, signed);
			String key = bookKey(instrumentKey, product);
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(fill);
			if (isBareNumericToken(rawKey.trim())) {
				byKey.computeIfAbsent(bookKey(rawKey.trim(), product), k -> new ArrayList<>()).add(fill);
			}
		}
		Map<String, Instant> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<TradeFill>> e : byKey.entrySet()) {
			Instant open = openOfCurrentNet(e.getValue());
			if (open != null) {
				out.put(e.getKey(), open);
			}
		}
		return out;
	}

	/** @deprecated Prefer {@link #openTimesFromTrades}; kept for callers/tests expecting earliest fill. */
	Map<String, Instant> earliestFillsFromTrades(JsonNode tradesJson) {
		return openTimesFromTrades(tradesJson);
	}

	record TradeFill(Instant ts, int signedQty) {
	}

	/**
	 * Chronological walk: entryAt = first fill after last flat that leaves a non-zero net.
	 * If the series never flats (window starts mid-open), use the earliest fill.
	 */
	static Instant openOfCurrentNet(List<TradeFill> fills) {
		if (fills == null || fills.isEmpty()) {
			return null;
		}
		List<TradeFill> sorted = new ArrayList<>(fills);
		sorted.sort(Comparator.comparing(TradeFill::ts));
		long running = 0;
		Instant openStart = null;
		Instant earliest = sorted.getFirst().ts();
		for (TradeFill f : sorted) {
			long before = running;
			running += f.signedQty();
			if (before == 0 && running != 0) {
				openStart = f.ts();
			}
			else if (running == 0) {
				openStart = null;
			}
		}
		if (running == 0) {
			return null;
		}
		return openStart != null ? openStart : earliest;
	}

	static int signedTradeQty(String side, int qty) {
		if (side == null || qty <= 0) {
			return 0;
		}
		String u = side.trim().toUpperCase(Locale.ROOT);
		if ("BUY".equals(u) || "B".equals(u) || u.startsWith("BUY")) {
			return qty;
		}
		if ("SELL".equals(u) || "S".equals(u) || u.startsWith("SELL")) {
			return -qty;
		}
		return 0;
	}

	static LocalDate tradeRowDate(JsonNode row, LocalDate fallback) {
		String raw = firstText(row, "trade_date", "fill_date", "order_date", "exchange_date", "date");
		if (raw != null && !raw.isBlank()) {
			try {
				String s = raw.trim();
				if (s.length() >= 10 && s.charAt(4) == '-') {
					return LocalDate.parse(s.substring(0, 10));
				}
				Instant asInst = parseInstant(s);
				if (asInst != null) {
					return asInst.atZone(IST).toLocalDate();
				}
			}
			catch (Exception ignored) {
				// fall through
			}
		}
		for (String field : List.of("order_timestamp", "exchange_timestamp", "trade_timestamp", "timestamp")) {
			String v = firstText(row, field);
			Instant parsed = parseInstant(v);
			if (parsed != null) {
				return parsed.atZone(IST).toLocalDate();
			}
		}
		return fallback;
	}

	static Instant parseTradeTimestamp(JsonNode row) {
		return parseTradeTimestamp(row, LocalDate.now(IST));
	}

	/** Try each timestamp field until one parses; time-only values use {@code day}. */
	static Instant parseTradeTimestamp(JsonNode row, LocalDate day) {
		if (row == null) {
			return null;
		}
		String[] fields = {
				"order_timestamp",
				"exchange_timestamp",
				"trade_timestamp",
				"fill_timestamp",
				"order_time",
				"exchange_time",
				"trade_time",
				"fill_time",
				"timestamp"
		};
		for (String field : fields) {
			String raw = firstText(row, field);
			if (raw == null || raw.isBlank()) {
				continue;
			}
			Instant full = parseInstant(raw);
			if (full != null) {
				return full;
			}
			Instant timeOnly = parseTimeOnlyOnDay(raw, day != null ? day : LocalDate.now(IST));
			if (timeOnly != null) {
				return timeOnly;
			}
		}
		return null;
	}

	static Instant parseTimeOnlyOnDay(String raw, LocalDate day) {
		if (raw == null || raw.isBlank() || day == null) {
			return null;
		}
		String s = raw.trim();
		try {
			LocalTime t;
			if (s.length() == 5) {
				t = LocalTime.parse(s);
			}
			else if (s.length() == 8 && s.charAt(2) == ':') {
				t = LocalTime.parse(s);
			}
			else {
				return null;
			}
			return day.atTime(t).atZone(IST).toInstant();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter IST_SPACE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter EXPIRY_DD_MMM_YYYY =
			DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

	/** Normalize broker expiry strings to ISO {@code YYYY-MM-DD}; null if unparseable. */
	static String normalizeExpiryToIso(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String s = raw.trim();
		try {
			return LocalDate.parse(s).toString();
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return LocalDate.parse(s, EXPIRY_DD_MMM_YYYY).toString();
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		// Compact forms: 01Dec2026 / 01-Dec-26
		String compact = s.replace("/", "-").replace(" ", "-");
		try {
			return LocalDate.parse(compact, DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)).toString();
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		if (compact.matches("(?i)\\d{1,2}[A-Za-z]{3}\\d{4}")) {
			try {
				return LocalDate.parse(compact, DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH)).toString();
			}
			catch (DateTimeParseException ignored) {
				return null;
			}
		}
		return null;
	}

	static Instant parseInstant(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String s = raw.trim();
		try {
			return Instant.parse(s);
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return LocalDateTime.parse(s, IST_SPACE).atZone(IST).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(IST).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static String mapMstockProduct(String raw) {
		if (raw == null || raw.isBlank()) {
			return "D";
		}
		String u = raw.toUpperCase(Locale.ROOT);
		if (u.contains("INTRA") || u.equals("MIS")) {
			return "I";
		}
		if (u.contains("CNC")) {
			return "CNC";
		}
		return "D";
	}

	public TradeSeedResult seedFromTrades(JsonNode tradesJson) {
		JsonNode data = tradesJson.path("data");
		if (!data.isArray() && tradesJson.isArray()) {
			data = tradesJson;
		}
		int tradeRows = data.isArray() ? data.size() : 0;
		Map<String, PositionItem> byKey = new LinkedHashMap<>();
		if (!data.isArray()) {
			return new TradeSeedResult(byKey, 0, 0, 0);
		}

		Map<String, TradeBucket> buckets = new LinkedHashMap<>();
		int skipped = 0;
		int nfoTrades = 0;
		for (JsonNode row : data) {
			String rawKey = firstText(row, "instrument_token", "instrument_key", "instrumentKey",
					"symboltoken", "symbol_token");
			if (rawKey == null || rawKey.isBlank()) {
				skipped++;
				continue;
			}
			String rawProduct = firstText(row, "product");
			String product = (rawProduct == null || rawProduct.isBlank()) ? "D" : rawProduct;
			String side = firstText(row, "transaction_type");
			int qty = (int) Math.round(number(row, "quantity", 0));
			double price = number(row, "average_price", 0);
			if (qty <= 0 || side == null) {
				skipped++;
				continue;
			}
			String exchange = firstText(row, "exchange");
			String symbol = firstText(row, "trading_symbol", "tradingsymbol");
			String instrumentKey = resolveTradeInstrumentKey(rawKey, exchange, symbol);
			String blob = ((exchange != null ? exchange : "") + " "
					+ (symbol != null ? symbol : "") + " "
					+ instrumentKey).toUpperCase(Locale.ROOT);
			if (blob.contains("NFO") || blob.contains("NSE_FO") || blob.contains("NIFTY")
					|| blob.contains("BSE_FO") || blob.contains("BFO")) {
				nfoTrades++;
			}

			String bucketKey = bookKey(instrumentKey, product);
			TradeBucket bucket = buckets.computeIfAbsent(bucketKey,
					k -> new TradeBucket(instrumentKey, product, exchange, symbol));
			LocalDate rowDay = tradeRowDate(row, LocalDate.now(IST));
			Instant fillTs = parseTradeTimestamp(row, rowDay);
			if (fillTs != null
					&& (bucket.earliestFill == null || fillTs.isBefore(bucket.earliestFill))) {
				bucket.earliestFill = fillTs;
			}
			double value = qty * price;
			if ("BUY".equalsIgnoreCase(side)) {
				bucket.buyQty += qty;
				bucket.buyValue += value;
			}
			else if ("SELL".equalsIgnoreCase(side)) {
				bucket.sellQty += qty;
				bucket.sellValue += value;
			}
			else {
				skipped++;
			}
		}

		for (Map.Entry<String, TradeBucket> entry : buckets.entrySet()) {
			PositionItem item = fromTradeBucket(entry.getValue());
			byKey.put(bookKey(item.instrumentKey(), item.product()), item);
		}
		// Overlay open-of-current-net (handles flat-then-reopen better than earliestFill).
		TradeSeedResult seeded = new TradeSeedResult(byKey, tradeRows, skipped, nfoTrades);
		return withEntryAts(seeded, tradesJson);
	}

	public PositionItem fromPositionUpdate(JsonNode row) {
		String rawKey = firstText(row, "instrument_token", "instrument_key", "instrumentKey",
				"symboltoken", "symbol_token");
		if (rawKey == null || rawKey.isBlank()) {
			return null;
		}
		String rawProduct = firstText(row, "product");
		String product = (rawProduct == null || rawProduct.isBlank()) ? "D" : rawProduct;
		String exchange = firstText(row, "exchange");
		String symbol = firstText(row, "trading_symbol", "tradingsymbol");
		String instrumentKey = resolveTradeInstrumentKey(rawKey, exchange, symbol);
		String tokenHint = isBareNumericToken(rawKey.trim()) ? rawKey.trim() : tokenFromKey(instrumentKey);
		int quantity = (int) Math.round(number(row, "quantity", 0));
		double averagePrice = number(row, "average_price", 0);
		if (averagePrice == 0) {
			averagePrice = quantity >= 0
					? number(row, "buy_price", 0)
					: number(row, "sell_price", 0);
		}
		double dayBuyQty = number(row, "day_buy_quantity", 0);
		double daySellQty = number(row, "day_sell_quantity", 0);
		double dayBuyValue = number(row, "day_buy_value", 0);
		double daySellValue = number(row, "day_sell_value", 0);
		double realised = 0;
		int matched = (int) Math.min(dayBuyQty, daySellQty);
		if (matched > 0 && dayBuyQty > 0 && daySellQty > 0) {
			double buyAvg = dayBuyValue / dayBuyQty;
			double sellAvg = daySellValue / daySellQty;
			realised = matched * (sellAvg - buyAvg);
		}
		else if (quantity == 0) {
			realised = daySellValue - dayBuyValue
					+ number(row, "overnight_sell_amount", 0)
					- number(row, "overnight_buy_amount", 0);
		}

		return enrich(
				instrumentKey,
				symbol,
				exchange,
				product,
				quantity,
				averagePrice,
				realised,
				0,
				tokenHint,
				inferType(symbol),
				null,
				null,
				null);
	}

	public PositionsResponse toResponse(
			Map<String, PositionItem> byKey,
			BrokerProfile profile,
			int tradeRows,
			int skipped,
			int nfoTrades,
			String source) {
		List<PositionItem> open = new ArrayList<>();
		List<PositionItem> closed = new ArrayList<>();
		for (PositionItem item : byKey.values()) {
			if (item.open()) {
				open.add(item);
			}
			else {
				closed.add(item);
			}
		}
		String userId = profile != null ? profile.userId() : null;
		String userName = profile != null ? profile.userName() : null;
		return new PositionsResponse(
				group(open),
				group(closed),
				tradeRows,
				skipped,
				userId,
				userName,
				tradeRows,
				nfoTrades,
				source);
	}

	public static String bookKey(String instrumentKey, String product) {
		String p = product == null || product.isBlank() ? "D" : product;
		return instrumentKey + "|" + p;
	}

	public static boolean isTerminalOrderStatus(String status) {
		if (status == null || status.isBlank()) {
			return false;
		}
		String s = status.trim().toLowerCase(Locale.ROOT);
		return s.equals("complete")
				|| s.equals("completed")
				|| s.equals("cancelled")
				|| s.equals("canceled")
				|| s.equals("rejected")
				|| s.contains("complete")
				|| s.contains("cancel")
				|| s.contains("reject");
	}

	private PositionItem fromTradeBucket(TradeBucket bucket) {
		int netQty = bucket.buyQty - bucket.sellQty;
		double buyAvg = bucket.buyQty > 0 ? bucket.buyValue / bucket.buyQty : 0;
		double sellAvg = bucket.sellQty > 0 ? bucket.sellValue / bucket.sellQty : 0;
		int matched = Math.min(bucket.buyQty, bucket.sellQty);
		double realised = matched > 0 ? matched * (sellAvg - buyAvg) : 0;
		if (netQty == 0) {
			realised = bucket.sellValue - bucket.buyValue;
		}
		double averagePrice = netQty > 0 ? buyAvg : (netQty < 0 ? sellAvg : (buyAvg != 0 ? buyAvg : sellAvg));
		String tokenHint = tokenFromKey(bucket.instrumentKey);
		if (!isBareNumericToken(tokenHint)) {
			tokenHint = null;
		}
		PositionItem item = enrich(
				bucket.instrumentKey,
				bucket.tradingSymbol,
				bucket.exchange,
				bucket.product,
				netQty,
				averagePrice,
				realised,
				0,
				tokenHint,
				inferType(bucket.tradingSymbol),
				null,
				null,
				null);
		return bucket.earliestFill != null ? withEntryAt(item, bucket.earliestFill) : item;
	}

	private PositionItem enrich(
			String instrumentKey,
			String tradingSymbol,
			String exchange,
			String product,
			int quantity,
			double averagePrice,
			double realised,
			double lastPrice) {
		return enrich(
				instrumentKey, tradingSymbol, exchange, product, quantity, averagePrice, realised, lastPrice,
				null, null, null, null, null);
	}

	private PositionItem enrich(
			String instrumentKey,
			String tradingSymbol,
			String exchange,
			String product,
			int quantity,
			double averagePrice,
			double realised,
			double lastPrice,
			String tokenHint) {
		return enrich(
				instrumentKey, tradingSymbol, exchange, product, quantity, averagePrice, realised, lastPrice,
				tokenHint, null, null, null, null);
	}

	private PositionItem enrich(
			String instrumentKey,
			String tradingSymbol,
			String exchange,
			String product,
			int quantity,
			double averagePrice,
			double realised,
			double lastPrice,
			String tokenHint,
			String rowInstrumentType,
			Double rowStrike,
			String rowExpiry,
			Integer rowLotSize) {
		String effectiveHint = tokenHint;
		if ((effectiveHint == null || effectiveHint.isBlank()) && isBareNumericToken(instrumentKey)) {
			effectiveHint = instrumentKey.trim();
		}
		else if ((effectiveHint == null || effectiveHint.isBlank())
				&& instrumentKey != null
				&& instrumentKey.contains(":")
				&& isBareNumericToken(tokenFromKey(instrumentKey))) {
			effectiveHint = tokenFromKey(instrumentKey);
		}
		InstrumentRecord rec = instrumentService != null
				? instrumentService.resolveRecord(instrumentKey, tradingSymbol, effectiveHint).orElse(null)
				: null;
		// Prefer token-based feed key so LTP/WS keep working even when symbol is MStock display-only.
		String resolvedKey = instrumentKey;
		if (effectiveHint != null && !effectiveHint.isBlank() && instrumentService != null) {
			String feedKey = instrumentService.ensureFeedKeyForToken(
					exchange,
					effectiveHint,
					rowInstrumentType != null ? rowInstrumentType : inferType(tradingSymbol));
			if (feedKey != null && !feedKey.isBlank()) {
				resolvedKey = feedKey;
			}
		}
		else if (isBareNumericToken(instrumentKey)) {
			resolvedKey = syntheticFoKey(
					exchange,
					rowInstrumentType != null ? rowInstrumentType : inferType(tradingSymbol),
					instrumentKey.trim());
		}
		String underlyingKey;
		String underlyingSymbol;
		String instrumentType;
		Double strike = rowStrike;
		String expiry;
		Integer lotSize = rowLotSize;
		if (rec != null) {
			if (tokenHint == null || tokenHint.isBlank()) {
				resolvedKey = rec.instrumentKey();
			}
			underlyingSymbol = rec.underlyingSymbol() != null ? rec.underlyingSymbol() : rec.tradingSymbol();
			instrumentType = rec.instrumentType();
			if (strike == null) {
				strike = rec.strikePrice();
			}
			if (rec.expiry() != null) {
				expiry = rec.expiry().toString();
			}
			else {
				expiry = normalizeExpiryToIso(rowExpiry);
			}
			if (lotSize == null) {
				lotSize = rec.lotSize();
			}
			if (tradingSymbol == null || tradingSymbol.isBlank()) {
				tradingSymbol = rec.tradingSymbol();
			}
			if (exchange == null || exchange.isBlank()) {
				exchange = rec.exchange();
			}
		}
		else {
			underlyingSymbol = inferUnderlyingSymbol(tradingSymbol);
			instrumentType = rowInstrumentType != null && !rowInstrumentType.isBlank()
					? rowInstrumentType
					: inferType(tradingSymbol);
			expiry = normalizeExpiryToIso(rowExpiry);
		}
		instrumentType = normalizeInstrumentType(instrumentType, tradingSymbol, rowInstrumentType);
		if (underlyingSymbol == null || underlyingSymbol.isBlank()) {
			underlyingSymbol = inferUnderlyingSymbol(tradingSymbol);
		}
		underlyingKey = canonicalUnderlyingKey(underlyingSymbol);

		double unrealised = 0;
		if (quantity != 0 && lastPrice > 0 && averagePrice > 0) {
			unrealised = quantity * (lastPrice - averagePrice);
		}
		double pnl = realised + unrealised;

		return new PositionItem(
				resolvedKey,
				tradingSymbol,
				exchange,
				product,
				quantity,
				averagePrice,
				lastPrice,
				pnl,
				realised,
				unrealised,
				underlyingKey,
				underlyingSymbol,
				instrumentType,
				strike,
				expiry,
				lotSize,
				quantity != 0,
				null);
	}

	private static Double numberOrNull(JsonNode row, String... fields) {
		for (String field : fields) {
			JsonNode value = row.get(field);
			if (value == null || value.isNull()) {
				continue;
			}
			double n = number(row, field, Double.NaN);
			if (!Double.isNaN(n)) {
				return n;
			}
		}
		return null;
	}

	private List<PositionGroup> group(List<PositionItem> items) {
		Map<String, List<PositionItem>> byUnderlying = new LinkedHashMap<>();
		for (PositionItem item : items) {
			String key = item.underlyingKey() != null && !item.underlyingKey().isBlank()
					? item.underlyingKey()
					: canonicalUnderlyingKey(item.underlyingSymbol() != null
							? item.underlyingSymbol()
							: inferUnderlyingSymbol(item.tradingSymbol()));
			byUnderlying.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
		}
		List<PositionGroup> groups = new ArrayList<>();
		for (Map.Entry<String, List<PositionItem>> entry : byUnderlying.entrySet()) {
			List<PositionItem> positions = entry.getValue();
			positions.sort(Comparator.comparing(PositionItem::tradingSymbol, Comparator.nullsLast(String::compareTo)));
			String symbol = positions.getFirst().underlyingSymbol();
			groups.add(new PositionGroup(entry.getKey(), symbol, positions));
		}
		groups.sort(Comparator.comparing(PositionGroup::underlyingSymbol, Comparator.nullsLast(String::compareTo)));
		return groups;
	}

	/** Spot annexure keys so LTP subscribe + grouping stay consistent. */
	static String canonicalUnderlyingKey(String underlyingSymbol) {
		if (underlyingSymbol == null || underlyingSymbol.isBlank()) {
			return "UNKNOWN";
		}
		String upper = underlyingSymbol.trim().toUpperCase(Locale.ROOT);
		if (upper.contains(":")) {
			upper = upper.substring(upper.indexOf(':') + 1);
		}
		return switch (upper) {
			case "NIFTY", "NIFTY50" -> "NSE:NIFTY50";
			case "BANKNIFTY", "NIFTYBANK" -> "NSE:NIFTYBANK";
			case "FINNIFTY" -> "NSE:FINNIFTY";
			case "MIDCPNIFTY" -> "NSE:MIDCPNIFTY";
			case "SENSEX" -> "BSE:SENSEX";
			case "BANKEX" -> "BSE:BANKEX";
			default -> {
				String ex = switch (upper) {
					case "SENSEX", "BANKEX" -> "BSE";
					default -> "NSE";
				};
				yield ex + ":" + upper;
			}
		};
	}

	static String normalizeInstrumentType(String type, String tradingSymbol, String rowType) {
		String candidate = firstNonBlank(type, rowType, inferType(tradingSymbol));
		if (candidate == null) {
			return "EQ";
		}
		String u = candidate.toUpperCase(Locale.ROOT);
		if ("CE".equals(u) || "PE".equals(u) || "FUT".equals(u) || "EQ".equals(u)) {
			return u;
		}
		if (u.contains("FUT")) {
			return "FUT";
		}
		if (u.contains("OPT") || u.contains("CE") || u.contains("PE")) {
			String fromSymbol = inferType(tradingSymbol);
			if ("CE".equals(fromSymbol) || "PE".equals(fromSymbol) || "FUT".equals(fromSymbol)) {
				return fromSymbol;
			}
			if (u.endsWith("CE") || "CE".equals(u)) {
				return "CE";
			}
			if (u.endsWith("PE") || "PE".equals(u)) {
				return "PE";
			}
		}
		String fromSymbol = inferType(tradingSymbol);
		return fromSymbol != null ? fromSymbol : u;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}

	private static String inferUnderlyingSymbol(String tradingSymbol) {
		if (tradingSymbol == null || tradingSymbol.isBlank()) {
			return "UNKNOWN";
		}
		String upper = tradingSymbol.toUpperCase(Locale.ROOT);
		for (String known : List.of("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX", "BANKEX")) {
			if (upper.startsWith(known)) {
				return known;
			}
		}
		String cleaned = upper.replaceAll("\\d.*", "");
		return cleaned.isBlank() ? upper : cleaned;
	}

	private static String inferType(String tradingSymbol) {
		if (tradingSymbol == null) {
			return "EQ";
		}
		String upper = tradingSymbol.toUpperCase(Locale.ROOT);
		if (upper.endsWith("CE")) {
			return "CE";
		}
		if (upper.endsWith("PE")) {
			return "PE";
		}
		if (upper.contains("FUT")) {
			return "FUT";
		}
		return "EQ";
	}

	static String firstText(JsonNode node, String... fields) {
		for (String field : fields) {
			String value = text(node, field);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	static double number(JsonNode node, String field, double fallback) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return fallback;
		}
		if (value.isNumber()) {
			return value.asDouble();
		}
		if (value.isString()) {
			String raw = value.asString().trim();
			if (raw.isEmpty()) {
				return fallback;
			}
			try {
				return Double.parseDouble(raw);
			}
			catch (NumberFormatException ex) {
				return fallback;
			}
		}
		return fallback;
	}

	private static final class TradeBucket {
		final String instrumentKey;
		final String product;
		final String exchange;
		final String tradingSymbol;
		int buyQty;
		double buyValue;
		int sellQty;
		double sellValue;
		Instant earliestFill;

		TradeBucket(String instrumentKey, String product, String exchange, String tradingSymbol) {
			this.instrumentKey = instrumentKey;
			this.product = product;
			this.exchange = exchange;
			this.tradingSymbol = tradingSymbol;
		}
	}
}

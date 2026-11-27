package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionChainResponse;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionSide;
import com.sgalgodesk.desk.application.optionchain.OptionChainDtos.OptionStrikeRow;

import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class MstockOptionChainService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	/** MStock Type A option-chain FO segment (docs use exch=2 for NSE index/equity FO). */
	private static final int OPTION_CHAIN_EXCH = 2;

	private static final String NIFTY = "NIFTY";
	private static final String NIFTY_CANONICAL_KEY = "NSE:NIFTY50";

	private static final Set<String> NIFTY_ALIASES = Set.of(
			"NIFTY", "NIFTY50", "NIFTY 50", "NIFTY50 INDEX", "NSE:NIFTY50", "NSE:NIFTY");

	private final MstockApiClient mstockApiClient;
	private final MstockInstrumentService mstockInstrumentService;
	private final BrokerAccess brokerAccess;
	private final BrokerSessionService brokerSessionService;

	public boolean isNiftyUnderlying(String instrumentKeyOrSymbol) {
		if (instrumentKeyOrSymbol == null || instrumentKeyOrSymbol.isBlank()) {
			return false;
		}
		String raw = instrumentKeyOrSymbol.trim().toUpperCase(Locale.ROOT);
		if (NIFTY_ALIASES.contains(raw)) {
			return true;
		}
		String sym = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
		return NIFTY_ALIASES.contains(sym) || "NIFTY".equals(sym) || "NIFTY50".equals(sym);
	}

	/**
	 * NIFTY expiries: Type A OPTIDX row dates merged with script-master CE/PE expiries.
	 * Prefer intersection when both agree; if they disagree (e.g. phantom dctExp Saturdays),
	 * prefer script master so algo/UI pick real weeklies on the first tick.
	 */
	public Mono<List<LocalDate>> listNiftyExpiries(UUID userId) {
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId)
						.then(Mono.defer(() -> brokerSessionService.translateAuthErrors(userId, ctx.broker(),
										mstockApiClient.getOptionChainMaster(
														ctx.apiKeyOrClientId(),
														ctx.accessToken(),
														OPTION_CHAIN_EXCH)
												.map(this::parseNiftyExpiryDates))
								.onErrorResume(err -> {
									log.warn("Option-chain master expiry list failed; using script master: {}",
											err.toString());
									return Mono.just(List.of());
								})
								.map(chainDates -> mergeExpiryLists(chainDates, scriptMasterNiftyExpiries())))));
	}

	private List<LocalDate> scriptMasterNiftyExpiries() {
		List<LocalDate> dates = mstockInstrumentService.expiries(NIFTY);
		if (dates.isEmpty()) {
			dates = mstockInstrumentService.expiries(NIFTY_CANONICAL_KEY);
		}
		return dates;
	}

	/**
	 * Merge chain-master and script-master expiry lists.
	 * Intersection when overlap exists; otherwise prefer script master; chain-only if script empty.
	 */
	static List<LocalDate> mergeExpiryLists(List<LocalDate> chainDates, List<LocalDate> scriptDates) {
		List<LocalDate> chain = chainDates == null ? List.of() : chainDates;
		List<LocalDate> script = scriptDates == null ? List.of() : scriptDates;
		if (script.isEmpty()) {
			return chain.stream().distinct().sorted().toList();
		}
		if (chain.isEmpty()) {
			return script.stream().distinct().sorted().toList();
		}
		Set<LocalDate> scriptSet = new LinkedHashSet<>(script);
		List<LocalDate> intersection = chain.stream()
				.filter(scriptSet::contains)
				.distinct()
				.sorted()
				.toList();
		if (!intersection.isEmpty()) {
			return intersection;
		}
		log.warn(
				"NIFTY chain-master expiries {} do not overlap script master {}; preferring script master",
				chain, script);
		return script.stream().distinct().sorted().toList();
	}

	public Mono<OptionChainResponse> getChain(UUID userId, String instrumentKey, String expiryDate) {
		if (!isNiftyUnderlying(instrumentKey)) {
			InstrumentRecord selected = mstockInstrumentService.findByKey(instrumentKey).orElse(null);
			String symbol = selected != null
					? (selected.underlyingSymbol() != null ? selected.underlyingSymbol() : selected.tradingSymbol())
					: instrumentKey;
			if (!isNiftyUnderlying(symbol)) {
				return Mono.error(new ValidationFailed("Only NIFTY option chain is supported for now"));
			}
		}
		LocalDate expiry = LocalDate.parse(expiryDate);
		return brokerAccess.requireActive(userId)
						.flatMap(ctx -> mstockInstrumentService.ensureLoaded(userId)
								.then(Mono.defer(() -> brokerSessionService.translateAuthErrors(userId, ctx.broker(),
										mstockApiClient.getOptionChainMaster(
														ctx.apiKeyOrClientId(),
														ctx.accessToken(),
														OPTION_CHAIN_EXCH)
												.flatMap(master -> {
													String[] row = findMasterRow(master, NIFTY);
													if (row == null || row.length < 2) {
														return Mono.error(new BrokerUnavailable("NIFTY missing from MStock option-chain master"));
													}
													String underlyingToken = row[1].trim();
													Long resolvedExpiry = resolveExpiryEpoch(master, row, expiry);
													if (resolvedExpiry == null) {
														List<LocalDate> available = parseNiftyExpiryDates(master);
														String hint = available.isEmpty()
																? emptyExpiryHint(master, row)
																: available.stream()
																		.map(LocalDate::toString)
																		.collect(Collectors.joining(", "));
														return Mono.error(new ValidationFailed(
																"No MStock option-chain expiry for NIFTY on "
																		+ expiryDate
																		+ ". Available: "
																		+ hint));
													}
													log.info(
															"MStock option chain request symbol=NIFTY exch={} expiry={} token={}",
															OPTION_CHAIN_EXCH,
															resolvedExpiry,
															underlyingToken);
													return mstockApiClient.getOptionChain(
															ctx.apiKeyOrClientId(),
															ctx.accessToken(),
															OPTION_CHAIN_EXCH,
															resolvedExpiry,
															underlyingToken);
												})
												.map(json -> parse(NIFTY_CANONICAL_KEY, NIFTY, expiryDate, json))))));
	}

	List<LocalDate> parseNiftyExpiryDates(JsonNode master) {
		return parseNiftyExpiryDates(master, LocalDate.now(IST));
	}

	/**
	 * Parse NIFTY expiries from Type A master OPTIDX row keys only.
	 * Tolerates epoch seconds or millis and string/number dctExp keys.
	 * Does not dump all dctExp values (those include non-NIFTY slots / sample Saturdays).
	 */
	static List<LocalDate> parseNiftyExpiryDates(JsonNode master, LocalDate today) {
		String[] row = findMasterRow(master, NIFTY);
		JsonNode dct = dctExpNode(master);
		if (row == null || dct == null || dct.isMissingNode() || dct.isNull()) {
			return List.of();
		}
		List<LocalDate> dates = new ArrayList<>();
		if (row.length >= 3) {
			for (int i = 2; i < row.length; i++) {
				String key = row[i].trim();
				if (key.isEmpty()) {
					continue;
				}
				Long epoch = dctEpoch(dct, key);
				if (epoch == null) {
					continue;
				}
				LocalDate d = epochToLocalDate(epoch);
				if (d != null && !d.isBefore(today)) {
					dates.add(d);
				}
			}
		}
		return dates.stream().distinct().sorted().toList();
	}

	private static String emptyExpiryHint(JsonNode master, String[] row) {
		JsonNode dct = dctExpNode(master);
		int dctSize = dct != null && dct.isObject() ? dct.size() : (dct != null && dct.isArray() ? dct.size() : 0);
		int rowExpirySlots = row != null && row.length > 2 ? row.length - 2 : 0;
		return "none listed (niftyRowSlots=" + rowExpirySlots + " dctExpSize=" + dctSize + ")";
	}

	private OptionChainResponse parse(String underlyingKey, String symbol, String expiryDate, JsonNode json) {
		JsonNode data = json.path("data");
		Map<Double, OptionSide> calls = new HashMap<>();
		Map<Double, OptionSide> puts = new HashMap<>();
		parseLegs(data.path("call"), "CE", calls);
		parseLegs(data.path("put"), "PE", puts);
		List<Double> strikes = new ArrayList<>();
		strikes.addAll(calls.keySet());
		strikes.addAll(puts.keySet());
		strikes = strikes.stream().distinct().sorted().toList();
		if (strikes.isEmpty()) {
			String status = text(json.path("status"));
			String message = text(json.path("message"));
			boolean callArr = data.path("call").isArray();
			boolean putArr = data.path("put").isArray();
			int callLen = callArr ? data.path("call").size() : -1;
			int putLen = putArr ? data.path("put").size() : -1;
			log.warn(
					"MStock option chain empty strikes symbol={} expiry={} status={} message={} callLen={} putLen={} dataNull={}",
					symbol,
					expiryDate,
					status,
					message,
					callLen,
					putLen,
					data.isMissingNode() || data.isNull());
		}
		List<OptionStrikeRow> rows = new ArrayList<>();
		for (Double strike : strikes) {
			rows.add(new OptionStrikeRow(strike, calls.get(strike), puts.get(strike), null));
		}
		rows.sort(Comparator.comparing(OptionStrikeRow::strike));
		String futuresKey = null;
		String futuresSymbol = null;
		JsonNode futures = data.path("future");
		if (futures.isArray() && !futures.isEmpty()) {
			String raw = text(futures.get(0));
			if (raw != null && raw.contains(",")) {
				String[] parts = raw.split(",");
				if (parts.length >= 1) {
					futuresKey = "NFO:" + parts[0].trim();
					futuresSymbol = symbol + " FUT";
				}
			}
		}
		Integer lotSize = mstockInstrumentService.findByKey(underlyingKey).map(InstrumentRecord::lotSize).orElse(null);
		return new OptionChainResponse(
				underlyingKey,
				symbol,
				null,
				expiryDate,
				null,
				null,
				lotSize,
				futuresKey,
				futuresSymbol,
				rows);
	}

	private void parseLegs(JsonNode arr, String kind, Map<Double, OptionSide> out) {
		if (!arr.isArray()) {
			return;
		}
		for (JsonNode node : arr) {
			String raw = text(node);
			if (raw == null) {
				continue;
			}
			String[] parts = raw.split(",");
			if (parts.length < 2) {
				continue;
			}
			String token = parts[0].trim();
			double strikeRaw = Double.parseDouble(parts[1].trim());
			double strike = strikeRaw > 10000 ? strikeRaw / 100.0 : strikeRaw;
			String tradingSymbol = kind + "-" + (int) strike;
			String instrumentKey = "NFO:" + token;
			out.put(strike, new OptionSide(
					instrumentKey,
					tradingSymbol,
					null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
		}
	}

	static Long resolveExpiryEpoch(JsonNode master, String[] masterRow, LocalDate preferredDate) {
		JsonNode dct = dctExpNode(master);
		if (dct == null || masterRow == null) {
			return null;
		}
		if (masterRow.length >= 3) {
			for (int i = 2; i < masterRow.length; i++) {
				String key = masterRow[i].trim();
				if (key.isEmpty()) {
					continue;
				}
				Long epoch = dctEpoch(dct, key);
				if (epoch == null) {
					continue;
				}
				LocalDate d = epochToLocalDate(epoch);
				if (preferredDate.equals(d)) {
					return normalizeEpochSeconds(epoch);
				}
			}
		}
		// Match preferred date against any dctExp entry when row keys failed or are absent.
		for (Long epoch : allDctEpochs(dct)) {
			LocalDate d = epochToLocalDate(epoch);
			if (preferredDate.equals(d)) {
				return normalizeEpochSeconds(epoch);
			}
		}
		return null;
	}

	static JsonNode dctExpNode(JsonNode master) {
		if (master == null) {
			return null;
		}
		JsonNode data = master.path("data");
		JsonNode dct = data.path("dctExp");
		if (!dct.isMissingNode() && !dct.isNull()) {
			return dct;
		}
		dct = data.path("DctExp");
		if (!dct.isMissingNode() && !dct.isNull()) {
			return dct;
		}
		dct = master.path("dctExp");
		return dct.isMissingNode() || dct.isNull() ? null : dct;
	}

	static Long dctEpoch(JsonNode dct, String key) {
		if (dct == null || key == null || key.isBlank()) {
			return null;
		}
		if (dct.isObject()) {
			JsonNode node = dct.get(key);
			if (node == null) {
				// Try numeric key forms ("1732320000" vs 1732320000).
				try {
					node = dct.get(String.valueOf(Long.parseLong(key.trim())));
				}
				catch (NumberFormatException ignored) {
					node = null;
				}
			}
			if (node != null && node.isNumber()) {
				return node.asLong();
			}
			if (node != null && node.isValueNode()) {
				try {
					return Long.parseLong(node.asString().trim());
				}
				catch (NumberFormatException ignored) {
					return null;
				}
			}
			return null;
		}
		return null;
	}

	static List<Long> allDctEpochs(JsonNode dct) {
		List<Long> out = new ArrayList<>();
		if (dct == null) {
			return out;
		}
		if (dct.isObject()) {
			dct.properties().forEach(e -> {
				JsonNode n = e.getValue();
				if (n != null && n.isNumber()) {
					out.add(n.asLong());
				}
				else if (n != null && n.isValueNode()) {
					try {
						out.add(Long.parseLong(n.asString().trim()));
					}
					catch (NumberFormatException ignored) {
						// skip
					}
				}
			});
		}
		else if (dct.isArray()) {
			for (JsonNode n : dct) {
				if (n != null && n.isNumber()) {
					out.add(n.asLong());
				}
			}
		}
		return out;
	}

	/** Epoch seconds for API path; accept millis from master. */
	static long normalizeEpochSeconds(long epoch) {
		// Heuristic: values past year ~2001 in millis are > 1e12.
		if (epoch > 1_000_000_000_000L) {
			return epoch / 1000L;
		}
		return epoch;
	}

	static LocalDate epochToLocalDate(long epoch) {
		long seconds = normalizeEpochSeconds(epoch);
		return Instant.ofEpochSecond(seconds).atZone(IST).toLocalDate();
	}

	static String[] findMasterRow(JsonNode master, String symbol) {
		Set<String> aliases = symbolAliases(symbol);
		JsonNode data = master != null ? master.path("data") : null;
		if (data == null || data.isMissingNode()) {
			return null;
		}
		String[] fromIdx = findRowInArray(data.path("OPTIDX"), aliases);
		if (fromIdx != null) {
			return fromIdx;
		}
		fromIdx = findRowInArray(data.path("OptIdx"), aliases);
		if (fromIdx != null) {
			return fromIdx;
		}
		String[] fromStk = findRowInArray(data.path("OFSTK"), aliases);
		if (fromStk != null) {
			return fromStk;
		}
		return findRowInArray(data.path("OfStk"), aliases);
	}

	private static String[] findRowInArray(JsonNode arr, Set<String> aliases) {
		if (!arr.isArray()) {
			return null;
		}
		for (JsonNode row : arr) {
			String raw = text(row);
			if (raw == null) {
				continue;
			}
			String[] parts = raw.split(",");
			if (parts.length >= 2 && aliases.contains(parts[0].trim().toUpperCase(Locale.ROOT))) {
				return parts;
			}
		}
		return null;
	}

	private static Set<String> symbolAliases(String symbol) {
		Set<String> out = new LinkedHashSet<>();
		if (symbol == null || symbol.isBlank()) {
			return out;
		}
		String upper = symbol.trim().toUpperCase(Locale.ROOT);
		out.add(upper);
		if (NIFTY_ALIASES.contains(upper) || "NIFTY".equals(upper) || "NIFTY50".equals(upper)) {
			out.addAll(NIFTY_ALIASES);
			out.add("NIFTY");
			out.add("NIFTY50");
		}
		return out;
	}

	private static String text(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()) {
			return null;
		}
		return node.asString();
	}
}

package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.domain.instruments.InstrumentRecord;
import com.sgalgodesk.desk.application.instruments.InstrumentSearchHit;
import com.sgalgodesk.desk.application.instruments.UnderlyingHit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
@Slf4j
public class MstockInstrumentService {

	private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

	/** MStock annexure index tokens — always seeded for quotes/WS. */
	private static final List<AnnexureIndex> ANNEXURE_INDICES = List.of(
			new AnnexureIndex(
					"NIFTY", "NSE", "NIFTY50", "26000", "Nifty 50",
					List.of("NIFTY50", "Nifty 50", "NIFTY 50", "NIFTY")),
			new AnnexureIndex(
					"BANKNIFTY", "NSE", "NIFTYBANK", "26009", "Nifty Bank",
					List.of("NIFTYBANK", "Nifty Bank", "NIFTY BANK", "BANKNIFTY")),
			new AnnexureIndex(
					"SENSEX", "BSE", "SENSEX", "51", "SENSEX",
					List.of("SENSEX")));

	private final AtomicReference<List<InstrumentRecord>> instruments = new AtomicReference<>(List.of());
	private final AtomicReference<Map<String, MstockFeedToken>> feedByKey =
			new AtomicReference<>(Map.of());
	private final AtomicReference<Map<String, MstockFeedToken>> feedByToken =
			new AtomicReference<>(Map.of());
	/** Canonical instrumentKey → Type A quote `i=` symbol (from script master or learned). */
	private final AtomicReference<Map<String, String>> quoteKeyByCanonical =
			new AtomicReference<>(Map.of());
	private final ConcurrentHashMap<String, String> learnedQuoteKeys = new ConcurrentHashMap<>();
	private final AtomicReference<Mono<Integer>> loading = new AtomicReference<>();
	private final MstockApiClient mstockApiClient;
	private final BrokerAccess brokerAccess;
	private final BrokerSessionService brokerSessionService;

	public Mono<Integer> ensureLoaded(UUID userId) {
		if (!instruments.get().isEmpty()) {
			return Mono.just(instruments.get().size());
		}
		return Mono.defer(() -> {
			Mono<Integer> created = refresh(userId).cache();
			if (loading.compareAndSet(null, created)) {
				return created.doFinally(signal -> loading.compareAndSet(created, null));
			}
			Mono<Integer> existing = loading.get();
			return existing != null ? existing : ensureLoaded(userId);
		});
	}

	public Mono<Integer> refresh(UUID userId) {
		return brokerAccess.requireActive(userId)
						.flatMap(ctx -> brokerSessionService.translateAuthErrors(userId, BrokerId.MSTOCK,
								mstockApiClient.getScriptMasterCsv(ctx.apiKeyOrClientId(), ctx.accessToken())
										.map(this::parseCsv)
										.map(this::withAnnexureIndices)
										.doOnNext(parsed -> {
											instruments.set(parsed.records());
											feedByKey.set(parsed.byKey());
											feedByToken.set(parsed.byToken());
											quoteKeyByCanonical.set(parsed.quoteByCanonical());
											log.info("Loaded {} MStock instruments ({} feed tokens); benchmarks {}",
													parsed.records().size(),
													parsed.byToken().size(),
													benchmarkSummary());
										})
										.map(parsed -> parsed.records().size())))
				.subscribeOn(Schedulers.boundedElastic());
	}

	public List<InstrumentRecord> all() {
		return instruments.get();
	}

	public Optional<InstrumentRecord> findByKey(String key) {
		if (key == null || key.isBlank()) {
			return Optional.empty();
		}
		return instruments.get().stream().filter(r -> key.equals(r.instrumentKey())).findFirst();
	}

	/**
	 * Resolve a broker-reported key/symbol/token to the script-master instrument key used by the market feed.
	 */
	public Optional<InstrumentRecord> resolveRecord(String instrumentKey, String tradingSymbol, String tokenHint) {
		Optional<InstrumentRecord> byKey = findByKey(instrumentKey);
		if (byKey.isPresent()) {
			return byKey;
		}
		if (tokenHint != null && !tokenHint.isBlank()) {
			String token = tokenHint.contains(":")
					? tokenHint.substring(tokenHint.indexOf(':') + 1).trim()
					: tokenHint.trim();
			MstockFeedToken feed = feedByToken.get().get(token);
			if (feed != null && feed.instrumentKey() != null) {
				Optional<InstrumentRecord> byToken = findByKey(feed.instrumentKey());
				if (byToken.isPresent()) {
					return byToken;
				}
			}
			if (instrumentKey != null && instrumentKey.contains(":")) {
				String afterColon = instrumentKey.substring(instrumentKey.indexOf(':') + 1).trim();
				if (afterColon.matches("\\d+")) {
					MstockFeedToken feed2 = feedByToken.get().get(afterColon);
					if (feed2 != null && feed2.instrumentKey() != null) {
						Optional<InstrumentRecord> byKeyToken = findByKey(feed2.instrumentKey());
						if (byKeyToken.isPresent()) {
							return byKeyToken;
						}
					}
				}
			}
		}
		if (tradingSymbol != null && !tradingSymbol.isBlank()) {
			String sym = tradingSymbol.trim();
			return instruments.get().stream()
					.filter(r -> sym.equalsIgnoreCase(r.tradingSymbol()))
					.findFirst();
		}
		return Optional.empty();
	}

	public String tokenForKey(String instrumentKey) {
		MstockFeedToken meta = feedByKey.get().get(instrumentKey);
		return meta != null ? meta.token() : null;
	}

	public MstockFeedToken feedTokenForKey(String instrumentKey) {
		return feedByKey.get().get(instrumentKey);
	}

	public MstockFeedToken feedTokenForToken(String token) {
		return feedByToken.get().get(token);
	}

	/**
	 * Ensure a market-feed / quote key exists for a broker symboltoken.
	 * Returns the canonical instrument key used by WS subscribe and /api/quotes/ltp.
	 */
	public String ensureFeedKeyForToken(String exchange, String token, String instrumentType) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String tok = token.trim();
		MstockFeedToken existing = feedByToken.get().get(tok);
		if (existing != null && existing.instrumentKey() != null) {
			return existing.instrumentKey();
		}
		String seg = foSegment(exchange, instrumentType);
		String key = seg + ":" + tok;
		int exchangeType = exchangeType(seg, seg, instrumentType);
		MstockFeedToken meta = new MstockFeedToken(tok, key, exchangeType);
		feedByToken.updateAndGet(prev -> {
			Map<String, MstockFeedToken> next = new HashMap<>(prev);
			next.put(tok, meta);
			return Map.copyOf(next);
		});
		feedByKey.updateAndGet(prev -> {
			Map<String, MstockFeedToken> next = new HashMap<>(prev);
			next.put(key, meta);
			return Map.copyOf(next);
		});
		return key;
	}

	private static String foSegment(String exchange, String instrumentType) {
		String ex = exchange == null ? "" : exchange.toUpperCase(Locale.ROOT);
		String t = instrumentType == null ? "" : instrumentType.toUpperCase(Locale.ROOT);
		boolean optOrFut = t.contains("OPT") || t.contains("CE") || t.contains("PE") || t.contains("FUT");
		if (ex.contains("BFO") || ex.equals("BSE") && optOrFut) {
			return "BFO";
		}
		if (optOrFut || ex.contains("NFO") || ex.equals("NSE") && optOrFut) {
			return "NFO";
		}
		if (ex.contains("BSE")) {
			return "BSE";
		}
		if (ex.contains("NFO")) {
			return "NFO";
		}
		return ex.isBlank() ? "NSE" : ex;
	}

	/** Snapshot of token → feed meta for market WS bridging. */
	public Map<String, MstockFeedToken> feedTokenIndex() {
		return feedByToken.get();
	}

	/** Snapshot of instrumentKey → feed meta (includes annexure aliases). */
	public Map<String, MstockFeedToken> feedKeyIndex() {
		return feedByKey.get();
	}

	/**
	 * Type A quote/LTP {@code i=} value for a canonical instrument key
	 * (e.g. NSE:NIFTY50 → NSE:Nifty 50 when that is what script master / API accepts).
	 */
	public String quoteKeyForInstrument(String canonicalKey) {
		if (canonicalKey == null || canonicalKey.isBlank()) {
			return canonicalKey;
		}
		String key = canonicalKey.trim();
		String learned = learnedQuoteKeys.get(key);
		if (learned != null) {
			return learned;
		}
		return quoteKeyByCanonical.get().getOrDefault(key, key);
	}

	/** Ordered quote-key candidates for probing Type A LTP/OHLC. */
	public List<String> quoteKeyCandidates(String canonicalKey) {
		if (canonicalKey == null || canonicalKey.isBlank()) {
			return List.of();
		}
		String key = canonicalKey.trim();
		Set<String> out = new LinkedHashSet<>();
		// Bare numeric tokens are not valid Type A `i=` values — resolve via feed index first.
		String tokenOnly = key.contains(":") ? key.substring(key.indexOf(':') + 1).trim() : key;
		boolean bareOrTokenKey = tokenOnly.chars().allMatch(Character::isDigit);
		if (bareOrTokenKey) {
			MstockFeedToken byToken = feedByToken.get().get(tokenOnly);
			if (byToken != null && byToken.instrumentKey() != null && !byToken.instrumentKey().isBlank()) {
				key = byToken.instrumentKey();
			}
			else if (!key.contains(":")) {
				String ensured = ensureFeedKeyForToken("NFO", tokenOnly, null);
				if (ensured != null && !ensured.isBlank()) {
					key = ensured;
				}
			}
		}
		String learned = learnedQuoteKeys.get(key);
		if (learned != null) {
			out.add(learned);
		}
		String preferred = quoteKeyByCanonical.get().get(key);
		if (preferred != null) {
			out.add(preferred);
		}
		// Never send bare digits to Type A quote API.
		if (!(key.chars().allMatch(Character::isDigit))) {
			out.add(key);
		}
		for (AnnexureIndex idx : ANNEXURE_INDICES) {
			if (idx.instrumentKey().equalsIgnoreCase(key)) {
				for (String sym : idx.symbolAliases()) {
					out.add(idx.exchange() + ":" + sym);
				}
				break;
			}
		}
		return List.copyOf(out);
	}

	/** Map a quote response key (or candidate) back to the canonical instrument key. */
	public String canonicalKeyForQuote(String quoteKey) {
		if (quoteKey == null || quoteKey.isBlank()) {
			return quoteKey;
		}
		String norm = quoteKey.replace("|", ":").trim();
		for (Map.Entry<String, String> e : learnedQuoteKeys.entrySet()) {
			if (e.getValue().equalsIgnoreCase(norm)) {
				return e.getKey();
			}
		}
		for (Map.Entry<String, String> e : quoteKeyByCanonical.get().entrySet()) {
			if (e.getValue().equalsIgnoreCase(norm) || e.getKey().equalsIgnoreCase(norm)) {
				return e.getKey();
			}
		}
		for (AnnexureIndex idx : ANNEXURE_INDICES) {
			if (idx.instrumentKey().equalsIgnoreCase(norm)) {
				return idx.instrumentKey();
			}
			for (String sym : idx.symbolAliases()) {
				if ((idx.exchange() + ":" + sym).equalsIgnoreCase(norm)) {
					return idx.instrumentKey();
				}
			}
		}
		return norm;
	}

	public void learnQuoteKey(String canonicalKey, String workingQuoteKey) {
		if (canonicalKey == null || workingQuoteKey == null
				|| canonicalKey.isBlank() || workingQuoteKey.isBlank()) {
			return;
		}
		learnedQuoteKeys.put(canonicalKey.trim(), workingQuoteKey.replace("|", ":").trim());
	}

	public List<InstrumentSearchHit> search(String query, String asset) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return List.of();
		}
		return instruments.get().stream()
				.filter(rec -> matches(rec, q, asset))
				.sorted(Comparator.comparing(InstrumentRecord::tradingSymbol, Comparator.nullsLast(String::compareTo)))
				.limit(20)
				.map(rec -> new InstrumentSearchHit(
						rec.instrumentKey(),
						rec.tradingSymbol(),
						rec.name(),
						rec.instrumentType(),
						rec.strikePrice(),
						rec.expiry(),
						rec.lotSize(),
						rec.tickSize(),
						rec.underlyingKey(),
						rec.underlyingSymbol(),
						rec.assetClass()))
				.toList();
	}

	public List<UnderlyingHit> searchUnderlyings(String query, String asset) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return List.of();
		}
		return instruments.get().stream()
				.filter(rec -> "index".equalsIgnoreCase(rec.assetClass()) || "EQ".equalsIgnoreCase(rec.instrumentType()))
				.filter(rec -> matches(rec, q, asset))
				.map(rec -> new UnderlyingHit(
						rec.instrumentKey(),
						rec.tradingSymbol(),
						rec.name(),
						rec.assetClass(),
						rec.lotSize()))
				.distinct()
				.limit(20)
				.toList();
	}

	public List<UnderlyingHit> benchmarks() {
		List<UnderlyingHit> out = new ArrayList<>();
		for (AnnexureIndex idx : ANNEXURE_INDICES) {
			String key = idx.instrumentKey();
			InstrumentRecord rec = findByKey(key).orElse(null);
			out.add(new UnderlyingHit(
					key,
					idx.display(),
					rec != null && rec.name() != null ? rec.name() : idx.name(),
					"index",
					rec != null ? rec.lotSize() : null));
		}
		return out;
	}

	private String benchmarkSummary() {
		Map<String, String> quotes = quoteKeyByCanonical.get();
		StringBuilder sb = new StringBuilder();
		for (AnnexureIndex idx : ANNEXURE_INDICES) {
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			String canonical = idx.instrumentKey();
			String quote = quotes.getOrDefault(canonical, canonical);
			sb.append(idx.display()).append("→").append(canonical)
					.append(" quote=").append(quote)
					.append(" (").append(idx.token()).append(')');
		}
		return sb.toString();
	}

	private Parsed withAnnexureIndices(Parsed parsed) {
		List<InstrumentRecord> records = new ArrayList<>(parsed.records());
		Map<String, MstockFeedToken> byKey = new HashMap<>(parsed.byKey());
		Map<String, MstockFeedToken> byToken = new HashMap<>(parsed.byToken());
		Map<String, String> quoteByCanonical = new HashMap<>();
		for (AnnexureIndex idx : ANNEXURE_INDICES) {
			String canonical = idx.instrumentKey();
			String quoteKey = resolveQuoteKeyFromMaster(parsed, idx);
			quoteByCanonical.put(canonical, quoteKey);
			MstockFeedToken feed = new MstockFeedToken(idx.token(), canonical, exchangeType(idx.exchange(), "IDX", "INDEX"));
			byKey.put(canonical, feed);
			byToken.put(idx.token(), feed);
			boolean present = records.stream().anyMatch(r -> canonical.equals(r.instrumentKey()));
			if (!present) {
				records.add(new InstrumentRecord(
						canonical,
						idx.tradingSymbol(),
						idx.name(),
						"INDEX",
						"IDX",
						idx.exchange(),
						idx.display(),
						canonical,
						null,
						"index",
						null,
						null,
						null,
						null));
			}
		}
		return new Parsed(
				List.copyOf(records),
				Map.copyOf(byKey),
				Map.copyOf(byToken),
				Map.copyOf(quoteByCanonical));
	}

	private String resolveQuoteKeyFromMaster(Parsed parsed, AnnexureIndex idx) {
		MstockFeedToken byTok = parsed.byToken().get(idx.token());
		if (byTok != null && byTok.instrumentKey() != null && !byTok.instrumentKey().isBlank()) {
			return byTok.instrumentKey();
		}
		for (String sym : idx.symbolAliases()) {
			String candidate = idx.exchange() + ":" + sym;
			if (parsed.byKey().containsKey(candidate)) {
				return candidate;
			}
		}
		for (InstrumentRecord rec : parsed.records()) {
			if (rec.tradingSymbol() == null || rec.exchange() == null) {
				continue;
			}
			if (!idx.exchange().equalsIgnoreCase(rec.exchange())) {
				continue;
			}
			for (String sym : idx.symbolAliases()) {
				if (sym.equalsIgnoreCase(rec.tradingSymbol())) {
					return rec.instrumentKey();
				}
			}
		}
		return idx.instrumentKey();
	}

	private record AnnexureIndex(
			String display,
			String exchange,
			String tradingSymbol,
			String token,
			String name,
			List<String> symbolAliases) {
		String instrumentKey() {
			return exchange + ":" + tradingSymbol;
		}
	}

	public List<LocalDate> expiries(String underlyingKey) {
		InstrumentRecord under = findByKey(underlyingKey).orElse(null);
		String raw = under != null
				? (under.underlyingSymbol() != null ? under.underlyingSymbol() : under.tradingSymbol())
				: underlyingKey;
		final String wantUnder = normalizeUnderlyingSymbol(raw);
		if (wantUnder.isBlank()) {
			return List.of();
		}
		return instruments.get().stream()
				.filter(r -> "CE".equalsIgnoreCase(r.instrumentType()) || "PE".equalsIgnoreCase(r.instrumentType()))
				.filter(r -> matchesUnderlying(wantUnder, r.underlyingSymbol(), r.tradingSymbol()))
				.map(InstrumentRecord::expiry)
				.filter(e -> e != null)
				.distinct()
				.sorted()
				.toList();
	}

	/**
	 * Find CE/PE option nearest to {@code strike} for underlying + expiry from script master.
	 */
	public Optional<InstrumentRecord> findOption(
			String underlyingSymbol,
			LocalDate expiry,
			String optionType,
			double strike) {
		if (underlyingSymbol == null || expiry == null || optionType == null) {
			return Optional.empty();
		}
		String type = optionType.trim().toUpperCase(Locale.ROOT);
		if (type.startsWith("C") || type.contains("CALL")) {
			type = "CE";
		}
		else {
			type = "PE";
		}
		final String wantType = type;
		final String wantUnder = normalizeUnderlyingSymbol(underlyingSymbol);
		return instruments.get().stream()
				.filter(r -> wantType.equalsIgnoreCase(r.instrumentType()))
				.filter(r -> r.expiry() != null && r.expiry().equals(expiry))
				.filter(r -> r.strikePrice() != null)
				.filter(r -> matchesUnderlying(wantUnder, r.underlyingSymbol(), r.tradingSymbol()))
				.min(Comparator.comparingDouble(r -> Math.abs(r.strikePrice() - strike)));
	}

	/** Canonical index name for lookup (NIFTY50 → NIFTY); leaves BANKNIFTY/FINNIFTY intact. */
	public static String normalizeUnderlyingSymbol(String underlyingSymbol) {
		if (underlyingSymbol == null || underlyingSymbol.isBlank()) {
			return "";
		}
		String under = underlyingSymbol.trim().toUpperCase(Locale.ROOT);
		if (under.contains(":")) {
			under = under.substring(under.indexOf(':') + 1);
		}
		if ("NIFTY50".equals(under) || "NIFTY 50".equals(under)) {
			return "NIFTY";
		}
		if (under.contains("NIFTY") && !under.contains("BANK") && !under.contains("FIN")
				&& !under.contains("MIDCP")) {
			return "NIFTY";
		}
		return under;
	}

	/**
	 * Exact underlying match — {@code "NIFTY"} must not match FINNIFTY/BANKNIFTY/MIDCPNIFTY.
	 * Trading symbols must start with the underlying followed by a digit (e.g. {@code NIFTY20NOV...}).
	 */
	public static boolean matchesUnderlying(String wantUnder, String underlyingSymbol, String tradingSymbol) {
		if (wantUnder == null || wantUnder.isBlank()) {
			return false;
		}
		String u = underlyingSymbol != null ? underlyingSymbol.toUpperCase(Locale.ROOT) : "";
		if (u.equals(wantUnder)) {
			return true;
		}
		String sym = tradingSymbol != null ? tradingSymbol.toUpperCase(Locale.ROOT) : "";
		if (sym.equals(wantUnder)) {
			return true;
		}
		if (sym.startsWith(wantUnder) && sym.length() > wantUnder.length()) {
			return Character.isDigit(sym.charAt(wantUnder.length()));
		}
		return false;
	}

	private Parsed parseCsv(String csv) {
		List<InstrumentRecord> list = new ArrayList<>();
		Map<String, MstockFeedToken> byKey = new HashMap<>();
		Map<String, MstockFeedToken> byToken = new HashMap<>();
		if (csv == null || csv.isBlank()) {
			return new Parsed(list, Map.copyOf(byKey), Map.copyOf(byToken), Map.of());
		}
		String[] lines = csv.split("\\R");
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] cols = line.split(",", -1);
			if (cols.length < 12) {
				continue;
			}
			String token = cols[0].trim();
			String tradingSymbol = cols[2].trim();
			String name = cols[3].trim();
			String expiryRaw = cols[5].trim();
			String strikeRaw = cols[6].trim();
			String tickRaw = cols[7].trim();
			String lotRaw = cols[8].trim();
			String type = cols[9].trim();
			String segment = cols[10].trim();
			String exchange = cols[11].trim();
			if (tradingSymbol.isEmpty() || exchange.isEmpty() || token.isEmpty()) {
				continue;
			}
			String instrumentKey = exchange + ":" + tradingSymbol;
			LocalDate expiry = parseExpiry(expiryRaw);
			Double strike = parseDouble(strikeRaw);
			Double tick = parseDouble(tickRaw);
			Integer lot = parseInt(lotRaw);
			String assetClass = inferAsset(type, segment);
			String underlyingSymbol = inferUnderlying(tradingSymbol, type);
			list.add(new InstrumentRecord(
					instrumentKey,
					tradingSymbol,
					name.isEmpty() ? tradingSymbol : name,
					type,
					segment,
					exchange,
					underlyingSymbol,
					underlyingSymbol != null ? exchange + ":" + underlyingSymbol : null,
					null,
					assetClass,
					strike,
					expiry,
					lot,
					tick));
			int exchangeType = exchangeType(exchange, segment, type);
			MstockFeedToken meta = new MstockFeedToken(token, instrumentKey, exchangeType);
			byKey.put(instrumentKey, meta);
			byToken.put(token, meta);
		}
		return new Parsed(list, Map.copyOf(byKey), Map.copyOf(byToken), Map.of());
	}

	static int exchangeType(String exchange, String segment, String type) {
		String ex = exchange == null ? "" : exchange.toUpperCase(Locale.ROOT);
		String seg = segment == null ? "" : segment.toUpperCase(Locale.ROOT);
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
		if (ex.contains("BFO") || seg.contains("BFO") || (ex.equals("BSE") && (seg.contains("FO") || isOptOrFut(t)))) {
			return 4; // BSEFO
		}
		if (ex.equals("BSE") || seg.contains("BSECM") || "SENSEX".equalsIgnoreCase(ex)) {
			return 3; // BSECM
		}
		if (ex.contains("NFO") || seg.contains("NFO") || seg.contains("FO") || isOptOrFut(t)) {
			return 2; // NSEFO
		}
		return 1; // NSECM (cash + indices)
	}

	private static boolean isOptOrFut(String type) {
		return "CE".equals(type) || "PE".equals(type) || "FUT".equals(type) || type.contains("OPT");
	}

	private static LocalDate parseExpiry(String expiryRaw) {
		if (expiryRaw == null || expiryRaw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(expiryRaw, EXPIRY_FMT);
		}
		catch (Exception ignored) {
			try {
				return LocalDate.parse(expiryRaw);
			}
			catch (Exception ignored2) {
				return null;
			}
		}
	}

	private static boolean matches(InstrumentRecord rec, String q, String asset) {
		if (asset != null && !asset.isBlank() && !"all".equalsIgnoreCase(asset)
				&& rec.assetClass() != null && !asset.equalsIgnoreCase(rec.assetClass())) {
			return false;
		}
		String blob = ((rec.tradingSymbol() != null ? rec.tradingSymbol() : "") + " "
				+ (rec.name() != null ? rec.name() : "") + " "
				+ (rec.instrumentKey() != null ? rec.instrumentKey() : "")).toLowerCase(Locale.ROOT);
		return blob.contains(q);
	}

	private static String inferAsset(String type, String segment) {
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
		String s = segment == null ? "" : segment.toUpperCase(Locale.ROOT);
		if (t.contains("IDX") || "INDEX".equals(t)) {
			return "index";
		}
		if ("CE".equals(t) || "PE".equals(t) || "FUT".equals(t) || s.contains("FO")) {
			return "index";
		}
		return "equity";
	}

	private static String inferUnderlying(String tradingSymbol, String type) {
		if (tradingSymbol == null) {
			return null;
		}
		String upper = tradingSymbol.toUpperCase(Locale.ROOT);
		for (String known : List.of("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX", "BANKEX")) {
			if (upper.startsWith(known)) {
				return known;
			}
		}
		if ("EQ".equalsIgnoreCase(type)) {
			return tradingSymbol;
		}
		String cleaned = upper.replaceAll("\\d.*", "");
		return cleaned.isBlank() ? upper : cleaned;
	}

	private static Double parseDouble(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(raw);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer parseInt(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return (int) Double.parseDouble(raw);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private record Parsed(
			List<InstrumentRecord> records,
			Map<String, MstockFeedToken> byKey,
			Map<String, MstockFeedToken> byToken,
			Map<String, String> quoteByCanonical) {
	}
}

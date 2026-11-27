package com.sgalgodesk.desk.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tradable indexes and last-resort lot sizes from application.yml {@code instruments.*}.
 */
@ConfigurationProperties(prefix = "instruments")
public class InstrumentsProperties {

	/** Comma-separated symbols, e.g. {@code NIFTY} or {@code NIFTY,BANKNIFTY}. */
	private String tradable = "NIFTY";

	private Map<String, InstrumentDefault> defaults = new LinkedHashMap<>();

	public String getTradable() {
		return tradable;
	}

	public void setTradable(String tradable) {
		this.tradable = tradable;
	}

	public Map<String, InstrumentDefault> getDefaults() {
		return defaults;
	}

	public void setDefaults(Map<String, InstrumentDefault> defaults) {
		this.defaults = defaults != null ? defaults : new LinkedHashMap<>();
	}

	public List<String> tradableSymbols() {
		List<String> out = new ArrayList<>();
		if (tradable == null || tradable.isBlank()) {
			return out;
		}
		for (String part : tradable.split(",")) {
			String s = part.trim().toUpperCase(Locale.ROOT);
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * Configured lot-size fallback for a symbol, or {@code null} if missing.
	 */
	public Integer configuredLotSize(String symbol) {
		if (symbol == null || symbol.isBlank() || defaults == null || defaults.isEmpty()) {
			return null;
		}
		InstrumentDefault def = defaults.get(symbol.trim().toUpperCase(Locale.ROOT));
		if (def == null) {
			// try case-insensitive key match
			for (Map.Entry<String, InstrumentDefault> e : defaults.entrySet()) {
				if (e.getKey() != null && e.getKey().equalsIgnoreCase(symbol.trim())) {
					def = e.getValue();
					break;
				}
			}
		}
		if (def == null || def.getLotSize() == null || def.getLotSize() <= 0) {
			return null;
		}
		return def.getLotSize();
	}

	/**
	 * Last-resort lot size: configured default for symbol, else {@code absoluteFallback}.
	 */
	public int lotSizeFallback(String symbol, int absoluteFallback) {
		Integer configured = configuredLotSize(symbol);
		return configured != null ? configured : absoluteFallback;
	}

	public static String underlyingKeyForSymbol(String index) {
		if (index == null || index.isBlank()) {
			return "NSE:NIFTY50";
		}
		String raw = index.trim().toUpperCase(Locale.ROOT);
		if (raw.contains("BANK")) {
			return "NSE:NIFTYBANK";
		}
		if (raw.contains("SENSEX")) {
			return "BSE:SENSEX";
		}
		if (raw.contains("FIN")) {
			return "NSE:FINNIFTY";
		}
		return "NSE:NIFTY50";
	}

	public static class InstrumentDefault {
		private Integer lotSize;

		public Integer getLotSize() {
			return lotSize;
		}

		public void setLotSize(Integer lotSize) {
			this.lotSize = lotSize;
		}
	}
}

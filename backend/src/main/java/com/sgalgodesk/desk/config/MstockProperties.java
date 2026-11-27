package com.sgalgodesk.desk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mstock")
public record MstockProperties(
		String apiBaseUrl,
		String wsBaseUrl,
		/** Optional fixed egress IP for MStock Primary/Static IP whitelist. */
		String primaryIp,
		/**
		 * Public optionstrategy host for market-hours / holiday feeds
		 * (distinct from Type A {@code apiBaseUrl}).
		 */
		String optionStrategyBaseUrl) {
}

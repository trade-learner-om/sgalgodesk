package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Guardrail: live-price hub must not reinstate a REST LTP poller.
 * One-shot {@code silentSeed} after WS silence is allowed.
 */
class MstockMarketFeedHubNoRestTest {

	@Test
	void hubHasNoRestPollerMethods() {
		Set<String> names = Arrays.stream(MstockMarketFeedHub.class.getDeclaredMethods())
				.map(Method::getName)
				.collect(Collectors.toSet());
		for (Class<?> nested : MstockMarketFeedHub.class.getDeclaredClasses()) {
			Arrays.stream(nested.getDeclaredMethods()).map(Method::getName).forEach(names::add);
		}
		assertFalse(names.contains("startRestFallback"), "REST fallback poller must stay removed");
		assertFalse(names.contains("pollRestLtps"), "REST poll must stay removed");
		assertFalse(names.contains("emitRestLtps"), "REST emit poller must stay removed");
	}
}

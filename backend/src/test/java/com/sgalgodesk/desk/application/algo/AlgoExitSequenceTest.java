package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies cancel-SL-then-SELL-then-BUY sequencing used by exit paths.
 */
class AlgoExitSequenceTest {

	@Test
	void cancelThenExitOrderIsSellThenBuy() {
		List<String> sequence = new ArrayList<>();
		AlgoStrategyTrade buy = trade(0, "BUY", "NFO:BUY", "sl-buy");
		AlgoStrategyTrade sell = trade(1, "SELL", "NFO:SELL", "sl-sell");
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(List.of(buy, sell));

		// Mirrors AlgoStrategyService: cancel + exit both walk exit-ordered legs
		Mono<Void> flow = Flux.fromIterable(ordered)
				.filter(t -> t.slOrderId() != null)
				.concatMap(t -> Mono.fromRunnable(() -> sequence.add("CANCEL:" + t.slOrderId())))
				.thenMany(Flux.fromIterable(ordered)
						.concatMap(t -> Mono.fromRunnable(() -> {
							String exitSide = "BUY".equalsIgnoreCase(t.side()) ? "SELL" : "BUY";
							PlaceOrderBody body = new PlaceOrderBody(
									t.instrumentKey(),
									exitSide,
									t.quantity(),
									"I",
									"MARKET",
									"DAY",
									0.0,
									null,
									"tag",
									true);
							sequence.add("PLACE:" + body.transactionType() + ":" + body.instrumentKey());
						})))
				.then();

		flow.block();

		assertEquals(List.of(
				"CANCEL:sl-sell",
				"CANCEL:sl-buy",
				"PLACE:BUY:NFO:SELL",
				"PLACE:SELL:NFO:BUY"), sequence);
	}

	@Test
	void partialEntryUnwindUsesExitOrder() {
		List<AlgoStrategyTrade> filled = List.of(
				trade(0, "BUY", "NFO:BUY", "sl1"),
				trade(1, "SELL", "NFO:SELL", null));
		List<AlgoStrategyTrade> ordered = AlgoLegOrdering.orderTradesForExit(filled);
		assertEquals("SELL", ordered.get(0).side());
		assertEquals("BUY", ordered.get(1).side());
	}

	private static AlgoStrategyTrade trade(int idx, String side, String key, String slOrderId) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				idx,
				key,
				key,
				side,
				25,
				"LIVE",
				"entry-" + idx,
				slOrderId,
				90.0,
				100.0,
				null,
				0,
				"ENTRY",
				"OPEN",
				Instant.now(),
				null,
				25,
				24000.0,
				null,
				"CE");
	}
}

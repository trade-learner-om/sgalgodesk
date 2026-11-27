package com.sgalgodesk.desk.application.algo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketCalendarService;
import com.sgalgodesk.desk.application.algo.port.AlgoStrategyStorePort;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.positions.PositionBookService;
import com.sgalgodesk.desk.application.quote.QuoteService;
import com.sgalgodesk.desk.domain.algo.AlgoStrategy;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyEvent;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyRun;
import com.sgalgodesk.desk.domain.algo.AlgoStrategyTrade;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class AlgoStrategyServiceEntryClaimTest {

	private InMemoryAlgoStore store;
	private AlgoStrategyService service;
	private AlgoStrategyRun run;

	@BeforeEach
	void setUp() {
		store = new InMemoryAlgoStore();
		service = new AlgoStrategyService(
				store,
				null,
				null,
				null,
				null,
				null,
				JsonMapper.builder().build());
		run = new AlgoStrategyRun(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				LocalDate.of(2026, 11, 25),
				"LIVE",
				"WAITING_ENTRY",
				0,
				0,
				0,
				null,
				0,
				Instant.now(),
				null);
		store.runs.put(run.id(), run);
	}

	@Test
	void tryClaimEntrySucceedsOnlyFromWaitingEntry() {
		assertTrue(Boolean.TRUE.equals(service.tryClaimEntry(run).block()));
		assertEquals("ENTERING", store.runs.get(run.id()).lifecycleState());
		assertEquals(1, store.casAttempts.get());
	}

	@Test
	void concurrentClaimOnlyOneWins() {
		Boolean first = service.tryClaimEntry(run).block();
		Boolean second = service.tryClaimEntry(run).block();

		assertTrue(Boolean.TRUE.equals(first));
		assertFalse(Boolean.TRUE.equals(second));
		assertEquals("ENTERING", store.runs.get(run.id()).lifecycleState());
		assertEquals(2, store.casAttempts.get());
	}

	@Test
	void releaseEntryClaimIfIdleReleasesWhenNoOpenTrades() {
		store.runs.put(run.id(), run.withLifecycleState("ENTERING"));

		service.releaseEntryClaimIfIdle(run).block();

		assertEquals("WAITING_ENTRY", store.runs.get(run.id()).lifecycleState());
	}

	@Test
	void releaseEntryClaimIfIdleKeepsEnteringWhenPartialOpensExist() {
		store.runs.put(run.id(), run.withLifecycleState("ENTERING"));
		store.tradesByRun.computeIfAbsent(run.id(), id -> new ArrayList<>())
				.add(openTrade(run, 0));

		service.releaseEntryClaimIfIdle(run).block();

		assertEquals("ENTERING", store.runs.get(run.id()).lifecycleState());
	}

	private static AlgoStrategyTrade openTrade(AlgoStrategyRun run, int legIndex) {
		return new AlgoStrategyTrade(
				UUID.randomUUID(),
				run.strategyId(),
				run.id(),
				run.userId(),
				legIndex,
				"NFO:PE",
				"PE",
				"SELL",
				65,
				"LIVE",
				"ord-1",
				null,
				null,
				237.35,
				null,
				0,
				"ENTRY",
				"OPEN",
				Instant.now(),
				null,
				65,
				25800.0,
				"2026-12-01",
				"PE");
	}

	/** Minimal in-memory store covering claim/release paths. */
	private static final class InMemoryAlgoStore implements AlgoStrategyStorePort {
		final Map<UUID, AlgoStrategyRun> runs = new ConcurrentHashMap<>();
		final Map<UUID, List<AlgoStrategyTrade>> tradesByRun = new ConcurrentHashMap<>();
		final AtomicInteger casAttempts = new AtomicInteger();

		@Override
		public Mono<Boolean> compareAndSetLifecycle(UUID runId, String fromState, String toState) {
			casAttempts.incrementAndGet();
			AlgoStrategyRun current = runs.get(runId);
			if (current == null || !fromState.equals(current.lifecycleState())) {
				return Mono.just(false);
			}
			runs.put(runId, current.withLifecycleState(toState));
			return Mono.just(true);
		}

		@Override
		public Flux<AlgoStrategyTrade> findTradesByRun(UUID runId) {
			return Flux.fromIterable(tradesByRun.getOrDefault(runId, List.of()));
		}

		@Override
		public Mono<AlgoStrategy> findStrategyById(UUID id) {
			return Mono.empty();
		}

		@Override
		public Flux<AlgoStrategy> findStrategiesByUser(UUID userId) {
			return Flux.empty();
		}

		@Override
		public Flux<AlgoStrategy> findStrategiesByStatus(String status) {
			return Flux.empty();
		}

		@Override
		public Mono<AlgoStrategy> insertStrategy(AlgoStrategy strategy) {
			return Mono.just(strategy);
		}

		@Override
		public Mono<AlgoStrategy> updateStrategy(AlgoStrategy strategy) {
			return Mono.just(strategy);
		}

		@Override
		public Mono<Void> deleteStrategy(UUID id) {
			return Mono.empty();
		}

		@Override
		public Flux<AlgoStrategyRun> findRunsByStrategy(UUID strategyId) {
			return Flux.empty();
		}

		@Override
		public Mono<AlgoStrategyRun> findLatestRun(UUID strategyId) {
			return Mono.empty();
		}

		@Override
		public Flux<AlgoStrategyRun> findRunsByUser(UUID userId) {
			return Flux.empty();
		}

		@Override
		public Mono<AlgoStrategyRun> insertRun(AlgoStrategyRun run) {
			runs.put(run.id(), run);
			return Mono.just(run);
		}

		@Override
		public Mono<AlgoStrategyRun> updateRun(AlgoStrategyRun run) {
			runs.put(run.id(), run);
			return Mono.just(run);
		}

		@Override
		public Flux<AlgoStrategyTrade> findTradesByStrategy(UUID strategyId) {
			return Flux.empty();
		}

		@Override
		public Flux<AlgoStrategyTrade> findTradesByStrategyAndStatus(UUID strategyId, String status) {
			return Flux.empty();
		}

		@Override
		public Mono<AlgoStrategyTrade> insertTrade(AlgoStrategyTrade trade) {
			return Mono.just(trade);
		}

		@Override
		public Mono<AlgoStrategyTrade> updateTrade(AlgoStrategyTrade trade) {
			return Mono.just(trade);
		}

		@Override
		public Mono<List<AlgoStrategyTrade>> insertTrades(List<AlgoStrategyTrade> trades) {
			return Mono.just(trades);
		}

		@Override
		public Mono<List<AlgoStrategyTrade>> updateTrades(List<AlgoStrategyTrade> trades) {
			return Mono.just(trades);
		}

		@Override
		public Flux<AlgoStrategyEvent> findEventsByStrategy(UUID strategyId) {
			return Flux.empty();
		}

		@Override
		public Flux<AlgoStrategyEvent> findEventsByRun(UUID runId) {
			return Flux.empty();
		}

		@Override
		public Mono<AlgoStrategyEvent> insertEvent(AlgoStrategyEvent event) {
			return Mono.just(event);
		}
	}
}

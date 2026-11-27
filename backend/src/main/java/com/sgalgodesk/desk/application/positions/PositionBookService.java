package com.sgalgodesk.desk.application.positions;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockApiClient;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.application.positions.PositionBookMapper.BrokerProfile;
import com.sgalgodesk.desk.application.positions.PositionBookMapper.TradeSeedResult;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;
import com.sgalgodesk.desk.application.quote.QuoteService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionBookService {

	private static final Duration IDLE_GRACE = Duration.ofSeconds(30);
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	/** Calendar-day lookback for trade history used to derive position open times. */
	private static final int TRADE_LOOKBACK_DAYS = 14;

	private final MstockApiClient mstockApiClient;
	private final MstockInstrumentService mstockInstrumentService;
	private final BrokerSessionService brokerSessionService;
	private final BrokerAccess brokerAccess;
	private final QuoteService quoteService;
	private final PositionBookMapper mapper;

	private final ConcurrentHashMap<String, UserBook> books = new ConcurrentHashMap<>();

	@jakarta.annotation.PostConstruct
	void listenBrokerSwitch() {
		brokerSessionService.addActiveBrokerListener((userId, broker) -> evictUser(userId));
	}

	public void evictUser(UUID userId) {
		books.entrySet().removeIf(entry -> {
			if (entry.getKey().startsWith(userId + "|")) {
				entry.getValue().dispose();
				return true;
			}
			return false;
		});
	}

	private static String bookId(UUID userId, BrokerId broker) {
		return userId + "|" + broker.code();
	}

	public Mono<PositionsResponse> snapshot(UUID userId) {
		return ensureReady(userId).flatMap(book -> refreshBookLtps(book)
				.then(Mono.fromCallable(() -> {
					PositionsResponse response = book.snapshot();
					if (book.consumerCount.get() <= 0) {
						scheduleIdleTeardown(bookId(userId, book.broker), book);
					}
					return response;
				})));
	}

	/**
	 * Register a long-lived consumer (browser WS). Keeps the book alive while count &gt; 0.
	 */
	public Mono<UserBookHandle> acquire(UUID userId) {
		return ensureReady(userId).map(book -> {
			book.addConsumer();
			cancelIdleTeardown(book);
			return new UserBookHandle(userId, book);
		});
	}

	public void release(UserBookHandle handle) {
		if (handle == null) {
			return;
		}
		UserBook book = handle.book;
		int remaining = book.removeConsumer();
		if (remaining <= 0) {
			scheduleIdleTeardown(bookId(handle.userId, book.broker), book);
		}
	}

	public Flux<PositionsResponse> snapshots(UUID userId) {
		return ensureReady(userId).flatMapMany(book -> Flux.concat(
				Mono.fromCallable(book::snapshot),
				book.snapshotFlux()));
	}

	@PreDestroy
	void shutdown() {
		for (UserBook book : books.values()) {
			book.dispose();
		}
		books.clear();
	}

	private Mono<UserBook> ensureReady(UUID userId) {
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> {
					String id = bookId(userId, ctx.broker());
					UserBook existing = books.get(id);
					if (existing != null && existing.ready()) {
						cancelIdleTeardown(existing);
						return Mono.just(existing);
					}
					return brokerSessionService.translateAuthErrors(
							userId, ctx.broker(), startOrAttach(userId, ctx));
				});
	}

	private Mono<UserBook> startOrAttach(UUID userId, BrokerAccess.ActiveBrokerContext ctx) {
		String id = bookId(userId, ctx.broker());
		UserBook created = new UserBook(userId, ctx.broker());
		UserBook raced = books.putIfAbsent(id, created);
		UserBook book = raced != null ? raced : created;
		synchronized (book) {
			if (book.ready()) {
				return Mono.just(book);
			}
			if (book.starting) {
				return book.readyMono;
			}
			book.starting = true;
			book.readyMono = bootstrap(book, ctx)
					.doOnError(err -> {
						synchronized (book) {
							book.starting = false;
							book.readyMono = Mono.error(err);
						}
						books.remove(id, book);
						book.dispose();
					})
					.cache();
			return book.readyMono;
		}
	}

	private Mono<UserBook> bootstrap(UserBook book, BrokerAccess.ActiveBrokerContext ctx) {
		return mstockInstrumentService.ensureLoaded(ctx.userId())
				.then(mstockApiClient.getPositionsTypeB(ctx.apiKeyOrClientId(), ctx.accessToken())
						.map(mapper::seedFromMstockPositions)
						.flatMap(seed -> fetchTradesForOpenTimes(ctx)
								.map(trades -> mapper.withEntryAts(seed, trades))
								.onErrorReturn(seed))
						.onErrorResume(ex -> {
							log.warn(
									"MStock positions seed failed; falling back to trades book: {}",
									ex.toString());
							return fetchTradesForOpenTimes(ctx)
									.map(mapper::seedFromTrades);
						}))
				.flatMap(seed -> enrichSeedLtps(ctx, seed)
						.defaultIfEmpty(seed))
				.map(seed -> {
					BrokerProfile profile = new BrokerProfile("mstock", "MStock user");
					book.applySeed(profile, seed);
					book.markSeeded();
					log.info(
							"MStock position book ready userId={} open={} closed={} rows={}",
							book.userId, book.openCount(), book.closedCount(), seed.tradeRows());
					synchronized (book) {
						book.starting = false;
					}
					return book;
				});
	}

	/** Multi-day trades so overnight / carry opens are visible (positions API has no open time). */
	private Mono<JsonNode> fetchTradesForOpenTimes(BrokerAccess.ActiveBrokerContext ctx) {
		LocalDate to = LocalDate.now(IST);
		LocalDate from = to.minusDays(TRADE_LOOKBACK_DAYS);
		return mstockApiClient.getTrades(
				ctx.apiKeyOrClientId(),
				ctx.accessToken(),
				from.toString(),
				to.toString());
	}

	/**
	 * MStock positions payload has no LTP — fill lastPrice via live-price channel (WS).
	 */
	private Mono<TradeSeedResult> enrichSeedLtps(
			BrokerAccess.ActiveBrokerContext ctx,
			TradeSeedResult seed) {
		if (seed == null || seed.byKey().isEmpty()) {
			return Mono.just(seed);
		}
		return quoteLtps(ctx.userId(), seed.byKey())
				.map(priced -> {
					Map<String, PositionItem> next = new LinkedHashMap<>(seed.byKey());
					next.putAll(priced);
					return new TradeSeedResult(next, seed.tradeRows(), seed.skipped(), seed.nfoTrades());
				})
				.defaultIfEmpty(seed)
				.doOnNext(updated -> log.info(
						"Enriched position LTPs broker={} priced={}/{}",
						ctx.broker(),
						updated.byKey().values().stream().filter(i -> i.lastPrice() > 0).count(),
						updated.byKey().size()));
	}

	private Mono<Void> refreshBookLtps(UserBook book) {
		boolean needs = book.byKey.values().stream()
				.anyMatch(i -> i != null && i.quantity() != 0 && i.lastPrice() <= 0);
		if (!needs) {
			return Mono.empty();
		}
		return quoteLtps(book.userId, Map.copyOf(book.byKey))
				.doOnNext(book::applyPrices)
				.then()
				.onErrorResume(ex -> {
					log.debug("Position book LTP refresh skipped: {}", ex.toString());
					return Mono.empty();
				});
	}

	private Mono<Map<String, PositionItem>> quoteLtps(UUID userId, Map<String, PositionItem> byKey) {
		List<String> keys = new ArrayList<>();
		Map<String, String> instrumentToBookKey = new HashMap<>();
		for (Map.Entry<String, PositionItem> entry : byKey.entrySet()) {
			PositionItem item = entry.getValue();
			if (item == null || item.quantity() == 0) {
				continue;
			}
			String instrumentKey = item.instrumentKey();
			if (instrumentKey == null || instrumentKey.isBlank()) {
				continue;
			}
			keys.add(instrumentKey);
			instrumentToBookKey.put(instrumentKey, entry.getKey());
		}
		if (keys.isEmpty()) {
			return Mono.empty();
		}
		return quoteService.ltp(userId, keys)
				.map(res -> {
					Map<String, PositionItem> next = new LinkedHashMap<>();
					for (Map.Entry<String, Double> price : res.prices().entrySet()) {
						if (price.getValue() == null || price.getValue() <= 0) {
							continue;
						}
						String bookKey = instrumentToBookKey.get(price.getKey());
						if (bookKey == null) {
							continue;
						}
						PositionItem item = byKey.get(bookKey);
						if (item == null) {
							continue;
						}
						next.put(bookKey, mapper.withLastPrice(item, price.getValue()));
					}
					return next;
				})
				.filter(m -> !m.isEmpty())
				.onErrorResume(ex -> {
					log.warn("Position LTP from live-price channel failed: {}", ex.toString());
					return Mono.empty();
				});
	}

	private void scheduleIdleTeardown(String id, UserBook book) {
		cancelIdleTeardown(book);
		book.idleDisposable = Mono.delay(IDLE_GRACE, Schedulers.boundedElastic())
				.subscribe(tick -> {
					if (book.consumerCount.get() > 0) {
						return;
					}
					if (books.remove(id, book)) {
						log.info("Position book idle teardown id={}", id);
						book.dispose();
					}
				});
	}

	private void cancelIdleTeardown(UserBook book) {
		Disposable d = book.idleDisposable;
		if (d != null) {
			d.dispose();
			book.idleDisposable = null;
		}
	}

	public final class UserBookHandle {
		private final UUID userId;
		private final UserBook book;

		private UserBookHandle(UUID userId, UserBook book) {
			this.userId = userId;
			this.book = book;
		}

		public Flux<PositionsResponse> snapshots() {
			return Flux.concat(Mono.fromCallable(book::snapshot), book.snapshotFlux());
		}
	}

	final class UserBook {
		private final UUID userId;
		private final BrokerId broker;
		private final ConcurrentHashMap<String, PositionItem> byKey = new ConcurrentHashMap<>();
		private final AtomicInteger consumerCount = new AtomicInteger(0);
		private final Sinks.Many<PositionsResponse> snapshots =
				Sinks.many().multicast().onBackpressureBuffer(64, false);

		private volatile BrokerProfile profile;
		private volatile int tradeRows;
		private volatile int skipped;
		private volatile int nfoTrades;
		private volatile boolean starting;
		private volatile boolean seeded;
		private volatile Mono<UserBook> readyMono = Mono.empty();
		private volatile Disposable idleDisposable;
		private volatile boolean disposed;

		UserBook(UUID userId, BrokerId broker) {
			this.userId = userId;
			this.broker = broker;
		}

		boolean ready() {
			return !disposed && seeded;
		}

		void markSeeded() {
			this.seeded = true;
		}

		void addConsumer() {
			consumerCount.incrementAndGet();
		}

		int removeConsumer() {
			return consumerCount.updateAndGet(v -> Math.max(0, v - 1));
		}

		Flux<PositionsResponse> snapshotFlux() {
			return snapshots.asFlux();
		}

		synchronized void applySeed(BrokerProfile profile, TradeSeedResult seed) {
			this.profile = profile;
			this.tradeRows = seed.tradeRows();
			this.skipped = seed.skipped();
			this.nfoTrades = seed.nfoTrades();
			byKey.clear();
			byKey.putAll(seed.byKey());
			publish();
		}

		synchronized void applyPrices(Map<String, PositionItem> priced) {
			if (priced == null || priced.isEmpty()) {
				return;
			}
			byKey.putAll(priced);
			publish();
		}

		PositionsResponse snapshot() {
			return mapper.toResponse(
					Map.copyOf(byKey),
					profile,
					tradeRows,
					skipped,
					nfoTrades,
					PositionBookMapper.SOURCE_MSTOCK_POSITIONS);
		}

		int openCount() {
			return (int) byKey.values().stream().filter(PositionItem::open).count();
		}

		int closedCount() {
			return (int) byKey.values().stream().filter(i -> !i.open()).count();
		}

		private void publish() {
			snapshots.tryEmitNext(snapshot());
		}

		void dispose() {
			disposed = true;
			Disposable idle = idleDisposable;
			if (idle != null) {
				idle.dispose();
			}
			snapshots.tryEmitComplete();
		}
	}
}

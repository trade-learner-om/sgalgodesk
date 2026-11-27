package com.sgalgodesk.desk.application.algo;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketFeedHub;
import com.sgalgodesk.desk.application.broker.BrokerAccess;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Keeps the live-price hub leased for users with running algo strategies
 * so upstream MStock WS stays up even when no browser is connected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlgoLivePriceLease {

	private final MstockMarketFeedHub marketFeedHub;
	private final BrokerAccess brokerAccess;

	private final ConcurrentHashMap<UUID, MstockMarketFeedHub.ClientHandle> leases = new ConcurrentHashMap<>();

	public Mono<Void> ensure(UUID userId, Set<String> instrumentKeys) {
		if (userId == null) {
			return Mono.empty();
		}
		Set<String> keys = instrumentKeys == null ? Set.of() : Set.copyOf(instrumentKeys);
		return brokerAccess.requireActive(userId)
				.doOnNext(ctx -> {
					MstockMarketFeedHub.ClientHandle handle = leases.compute(userId, (id, existing) -> {
						if (existing != null) {
							return existing;
						}
						log.info("Algo live-price lease acquired for user {}", userId);
						return marketFeedHub.acquire(userId, ctx);
					});
					handle.setSubscriptions(keys);
				})
				.then()
				.onErrorResume(ex -> {
					log.debug("Algo live-price lease skipped for {}: {}", userId, ex.toString());
					return Mono.empty();
				});
	}

	public void release(UUID userId) {
		if (userId == null) {
			return;
		}
		MstockMarketFeedHub.ClientHandle handle = leases.remove(userId);
		if (handle != null) {
			handle.release();
			log.info("Algo live-price lease released for user {}", userId);
		}
	}
}

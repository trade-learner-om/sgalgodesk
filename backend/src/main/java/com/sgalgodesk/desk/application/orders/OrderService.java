package com.sgalgodesk.desk.application.orders;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.orders.OrderDtos.OrderBookEntryDto;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceMultiBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceMultiResult;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderResult;
import com.sgalgodesk.desk.application.orders.port.OrderPort;
import com.sgalgodesk.desk.application.orders.port.OrderPort.OrderBookEntry;
import com.sgalgodesk.desk.application.orders.port.OrderPort.OrderRequest;
import com.sgalgodesk.desk.application.orders.port.OrderPort.OrderResult;
import com.sgalgodesk.desk.domain.shared.BrokerUnavailable;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OrderService {

	private static final Duration FILL_POLL = Duration.ofMillis(750);
	private static final Duration FILL_TIMEOUT = Duration.ofSeconds(20);
	private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "COMPLETED", "FILLED");
	private static final Set<String> TERMINAL_FAIL_STATUSES = Set.of(
			"REJECTED", "CANCELLED", "CANCELED", "EXPIRED", "ERROR");

	private final OrderPort orderPort;

	public Mono<PlaceOrderResult> place(UUID userId, PlaceOrderBody body) {
		return orderPort.place(userId, toRequest(body)).map(this::toDto);
	}

	public Mono<PlaceMultiResult> placeMulti(UUID userId, PlaceMultiBody body) {
		List<OrderRequest> requests = body.orders().stream().map(OrderService::toRequest).toList();
		return orderPort.placeMulti(userId, requests)
				.map(results -> new PlaceMultiResult(results.stream().map(this::toDto).toList()));
	}

	public Mono<List<OrderBookEntryDto>> getOrders(UUID userId) {
		return orderPort.getOrders(userId).map(list -> list.stream().map(this::toBookDto).toList());
	}

	public Mono<Void> cancelOrder(UUID userId, String orderId) {
		return orderPort.cancelOrder(userId, orderId)
				.onErrorResume(err -> Mono.empty());
	}

	/**
	 * Polls the order book until the order reaches a completed fill state,
	 * or fails with {@link BrokerUnavailable} on reject/timeout.
	 */
	public Mono<OrderBookEntryDto> waitUntilComplete(UUID userId, String orderId) {
		if (orderId == null || orderId.isBlank()) {
			return Mono.error(new BrokerUnavailable("Missing order id for fill confirmation"));
		}
		return Mono.defer(() -> orderPort.getOrders(userId)
						.flatMap(entries -> {
							OrderBookEntry match = findById(entries, orderId);
							if (match == null) {
								return Mono.empty();
							}
							String status = normalizeStatus(match.status());
							if (COMPLETE_STATUSES.contains(status)) {
								return Mono.just(toBookDto(match));
							}
							if (TERMINAL_FAIL_STATUSES.contains(status)) {
								return Mono.error(new BrokerUnavailable(
										"Order " + orderId + " ended with status " + match.status()));
							}
							return Mono.empty();
						}))
				.repeatWhenEmpty(repeat -> repeat.delayElements(FILL_POLL))
				.timeout(FILL_TIMEOUT, Mono.error(new BrokerUnavailable(
						"Timed out waiting for order fill: " + orderId)));
	}

	private static OrderBookEntry findById(List<OrderBookEntry> entries, String orderId) {
		for (OrderBookEntry e : entries) {
			if (orderId.equals(e.orderId())) {
				return e;
			}
		}
		return null;
	}

	private static String normalizeStatus(String status) {
		return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
	}

	private static OrderRequest toRequest(PlaceOrderBody body) {
		return new OrderRequest(
				body.instrumentKey(),
				body.transactionType(),
				body.quantity(),
				body.product(),
				body.orderType(),
				body.validity(),
				body.price(),
				body.triggerPrice(),
				body.tag(),
				body.slice());
	}

	private PlaceOrderResult toDto(OrderResult result) {
		return new PlaceOrderResult(
				result.instrumentKey(),
				result.status(),
				result.orderIds(),
				result.raw());
	}

	private OrderBookEntryDto toBookDto(OrderBookEntry entry) {
		return new OrderBookEntryDto(
				entry.orderId(),
				entry.status(),
				entry.tradingsymbol(),
				entry.exchange(),
				entry.transactionType(),
				entry.orderType(),
				entry.averagePrice(),
				entry.filledQuantity(),
				entry.quantity(),
				entry.triggerPrice());
	}
}

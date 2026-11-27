package com.sgalgodesk.desk.application.orders.port;

import java.util.List;
import java.util.UUID;

import reactor.core.publisher.Mono;

/** Outbound port: order placement with the active broker. */
public interface OrderPort {

	Mono<OrderResult> place(UUID userId, OrderRequest order);

	Mono<List<OrderResult>> placeMulti(UUID userId, List<OrderRequest> orders);

	Mono<List<OrderBookEntry>> getOrders(UUID userId);

	Mono<Void> cancelOrder(UUID userId, String orderId);

	record OrderRequest(
			String instrumentKey,
			String transactionType,
			int quantity,
			String product,
			String orderType,
			String validity,
			Double price,
			Double triggerPrice,
			String tag,
			Boolean slice) {
	}

	record OrderResult(
			String instrumentKey,
			String status,
			List<String> orderIds,
			String raw) {
	}

	record OrderBookEntry(
			String orderId,
			String status,
			String tradingsymbol,
			String exchange,
			String transactionType,
			String orderType,
			double averagePrice,
			int filledQuantity,
			int quantity,
			double triggerPrice,
			String raw) {
	}
}

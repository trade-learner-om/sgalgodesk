package com.sgalgodesk.desk.adapters.outbound.mstock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerAccess.ActiveBrokerContext;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.orders.port.OrderPort;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class MstockOrderAdapter implements OrderPort {

	private final MstockApiClient mstockApiClient;
	private final BrokerAccess brokerAccess;
	private final BrokerSessionService brokerSessionService;

	@Override
	public Mono<OrderResult> place(UUID userId, OrderRequest order) {
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> brokerSessionService.translateAuthErrors(
						userId, ctx.broker(), placeOne(ctx, order)));
	}

	@Override
	public Mono<List<OrderResult>> placeMulti(UUID userId, List<OrderRequest> orders) {
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> brokerSessionService.translateAuthErrors(
						userId,
						ctx.broker(),
						Flux.fromIterable(orders)
								.concatMap(order -> placeOne(ctx, order)
										.onErrorResume(err -> Mono.just(new OrderResult(
												order.instrumentKey(),
												"error",
												List.of(),
												err.getMessage()))))
								.collectList()));
	}

	@Override
	public Mono<List<OrderBookEntry>> getOrders(UUID userId) {
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> brokerSessionService.translateAuthErrors(
						userId,
						ctx.broker(),
						mstockApiClient.getOrders(ctx.apiKeyOrClientId(), ctx.accessToken())
								.map(this::parseOrderBook)));
	}

	@Override
	public Mono<Void> cancelOrder(UUID userId, String orderId) {
		if (orderId == null || orderId.isBlank()) {
			return Mono.empty();
		}
		return brokerAccess.requireActive(userId)
				.flatMap(ctx -> brokerSessionService.translateAuthErrors(
						userId,
						ctx.broker(),
						mstockApiClient.cancelOrder(ctx.apiKeyOrClientId(), ctx.accessToken(), "regular", orderId)
								.then()));
	}

	private Mono<OrderResult> placeOne(ActiveBrokerContext ctx, OrderRequest body) {
		String instrumentKey = body.instrumentKey();
		String exchange;
		String tradingSymbol;
		int colon = instrumentKey.indexOf(':');
		if (colon > 0) {
			exchange = instrumentKey.substring(0, colon);
			tradingSymbol = instrumentKey.substring(colon + 1);
		}
		else {
			exchange = "NFO";
			tradingSymbol = instrumentKey;
		}
		String product = mapProduct(body.product());
		String orderType = body.orderType() != null && !body.orderType().isBlank() ? body.orderType() : "MARKET";
		String validity = body.validity() != null && !body.validity().isBlank() ? body.validity() : "DAY";
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("variety", "regular");
		fields.put("exchange", exchange);
		fields.put("tradingsymbol", tradingSymbol);
		fields.put("transaction_type", body.transactionType().toUpperCase(Locale.ROOT));
		fields.put("order_type", orderType);
		fields.put("quantity", String.valueOf(body.quantity()));
		fields.put("product", product);
		fields.put("validity", validity);
		fields.put("price", String.valueOf(body.price() != null ? body.price() : 0));
		if (body.triggerPrice() != null) {
			fields.put("trigger_price", String.valueOf(body.triggerPrice()));
		}
		if (body.tag() != null && !body.tag().isBlank()) {
			fields.put("tag", body.tag());
		}
		return mstockApiClient.placeOrder(ctx.apiKeyOrClientId(), ctx.accessToken(), "regular", fields)
				.map(json -> toResult(body.instrumentKey(), json));
	}

	private List<OrderBookEntry> parseOrderBook(JsonNode json) {
		List<OrderBookEntry> out = new ArrayList<>();
		JsonNode data = json.path("data");
		if (!data.isArray()) {
			return out;
		}
		for (JsonNode row : data) {
			String orderId = text(row, "order_id");
			if (orderId == null || orderId.isBlank()) {
				continue;
			}
			out.add(new OrderBookEntry(
					orderId,
					text(row, "status"),
					text(row, "tradingsymbol"),
					text(row, "exchange"),
					text(row, "transaction_type"),
					text(row, "order_type"),
					number(row, "average_price"),
					(int) number(row, "filled_quantity"),
					(int) number(row, "quantity"),
					number(row, "trigger_price"),
					row.toString()));
		}
		return out;
	}

	private static String mapProduct(String product) {
		if (product == null || product.isBlank() || "D".equalsIgnoreCase(product)) {
			return "NRML";
		}
		if ("I".equalsIgnoreCase(product) || "MIS".equalsIgnoreCase(product)) {
			return "MIS";
		}
		if ("CNC".equalsIgnoreCase(product)) {
			return "CNC";
		}
		return product.toUpperCase(Locale.ROOT);
	}

	private static OrderResult toResult(String instrumentKey, JsonNode json) {
		JsonNode data = json.path("data");
		List<String> ids = new ArrayList<>();
		JsonNode orderIds = data.path("order_ids");
		if (orderIds.isArray()) {
			for (JsonNode id : orderIds) {
				if (!id.isMissingNode() && !id.isNull() && id.isValueNode()) {
					ids.add(id.asString());
				}
			}
		}
		String single = text(data, "order_id");
		if (single != null && !single.isBlank()) {
			ids.add(single);
		}
		if (data.isValueNode() && !data.isMissingNode() && !data.isNull()) {
			ids.add(data.asString());
		}
		String status = text(json, "status");
		if (status == null) {
			status = ids.isEmpty() ? "unknown" : "success";
		}
		return new OrderResult(instrumentKey, status, ids, json.toString());
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.isMissingNode() || !value.isValueNode()) {
			return null;
		}
		return value.asString();
	}

	private static double number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.isMissingNode()) {
			return 0;
		}
		if (value.isNumber()) {
			return value.asDouble();
		}
		if (value.isValueNode()) {
			try {
				return Double.parseDouble(value.asString());
			}
			catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return 0;
	}
}

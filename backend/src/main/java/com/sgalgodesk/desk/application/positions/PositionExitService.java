package com.sgalgodesk.desk.application.positions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.orders.OrderDtos.ExitPositionsBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceMultiBody;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceMultiResult;
import com.sgalgodesk.desk.application.orders.OrderDtos.PlaceOrderBody;
import com.sgalgodesk.desk.application.orders.OrderService;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionItem;
import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;
import com.sgalgodesk.desk.domain.shared.ValidationFailed;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PositionExitService {

	private final PositionsService positionsService;
	private final OrderService orderService;
	private final BrokerSessionService brokerSessionService;

	public Mono<PlaceMultiResult> exit(UUID userId, ExitPositionsBody body) {
		if (body.instrumentKeys() == null || body.instrumentKeys().isEmpty()) {
			return Mono.error(new ValidationFailed("instrumentKeys required"));
		}
		return brokerSessionService.requireActiveSession(userId)
				.then(brokerSessionService.activeBroker(userId))
				.flatMap(broker -> brokerSessionService.translateAuthErrors(userId, broker,
						positionsService.listPositions(userId)
								.flatMap(positions -> {
									Map<String, PositionItem> openByKey = indexOpen(positions);
									List<PlaceOrderBody> orders = new ArrayList<>();
									for (String key : body.instrumentKeys()) {
										PositionItem item = openByKey.get(key);
										if (item == null || item.quantity() == 0) {
											continue;
										}
										int qty = Math.abs(item.quantity());
										String side = item.quantity() > 0 ? "SELL" : "BUY";
										String product = item.product() != null ? item.product() : "D";
										orders.add(new PlaceOrderBody(
												item.instrumentKey(),
												side,
												qty,
												product,
												"MARKET",
												"DAY",
												0.0,
												"exit-position",
												Boolean.TRUE));
									}
									if (orders.isEmpty()) {
										return Mono.error(new ValidationFailed("No open positions to exit"));
									}
									return orderService.placeMulti(userId, new PlaceMultiBody(orders));
								})));
	}

	private static Map<String, PositionItem> indexOpen(PositionsResponse positions) {
		Map<String, PositionItem> map = new HashMap<>();
		for (var group : positions.openGroups()) {
			for (PositionItem item : group.positions()) {
				map.put(item.instrumentKey(), item);
			}
		}
		return map;
	}
}

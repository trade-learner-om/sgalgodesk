package com.sgalgodesk.desk.application.orders;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class OrderDtos {

	private OrderDtos() {
	}

	public record PlaceOrderBody(
			@NotBlank String instrumentKey,
			@NotBlank String transactionType,
			@Min(1) int quantity,
			String product,
			String orderType,
			String validity,
			Double price,
			Double triggerPrice,
			String tag,
			Boolean slice) {

		/** Backward-compatible ctor used by existing call sites without trigger. */
		public PlaceOrderBody(
				String instrumentKey,
				String transactionType,
				int quantity,
				String product,
				String orderType,
				String validity,
				Double price,
				String tag,
				Boolean slice) {
			this(instrumentKey, transactionType, quantity, product, orderType, validity, price, null, tag, slice);
		}
	}

	public record PlaceMultiBody(
			@NotEmpty List<@NotNull PlaceOrderBody> orders) {
	}

	public record PlaceOrderResult(
			String instrumentKey,
			String status,
			List<String> orderIds,
			String raw) {
	}

	public record PlaceMultiResult(
			List<PlaceOrderResult> results) {
	}

	public record OrderBookEntryDto(
			String orderId,
			String status,
			String tradingsymbol,
			String exchange,
			String transactionType,
			String orderType,
			double averagePrice,
			int filledQuantity,
			int quantity,
			double triggerPrice) {
	}

	public record ExitPositionsBody(
			List<String> instrumentKeys) {
	}
}

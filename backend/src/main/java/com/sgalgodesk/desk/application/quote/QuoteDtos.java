package com.sgalgodesk.desk.application.quote;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

public final class QuoteDtos {

	private QuoteDtos() {
	}

	public record LtpRequest(@NotEmpty List<String> instrumentKeys) {
	}

	public record LtpResponse(Map<String, Double> prices) {
	}

	public record GreeksResponse(
			String instrumentKey,
			Double iv,
			Double delta,
			Double gamma,
			Double theta,
			Double vega
	) {
	}
}

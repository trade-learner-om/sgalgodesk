package com.sgalgodesk.desk.application.account;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

	private AccountDtos() {
	}

	public record SignupRequest(
			@NotBlank @Email String email,
			@NotBlank @Size(min = 8, max = 100) String password
	) {
	}

	public record LoginRequest(
			@NotBlank @Email String email,
			@NotBlank String password
	) {
	}

	public record PreferredUnderlying(
			String instrumentKey,
			String tradingSymbol,
			String assetClass
	) {
	}

	public record SaveSettingsRequest(
			@Size(max = 128) String instrumentKey,
			@Size(max = 64) String tradingSymbol,
			@Size(max = 32) String assetClass,
			@Size(max = 16) String activeBroker
	) {
	}

	public record BrokerStatus(
			String id,
			boolean credentialsConfigured,
			boolean linked,
			boolean tokenValid,
			String userName
	) {
	}

	public record MeResponse(
			String email,
			boolean credentialsConfigured,
			boolean brokerLinked,
			String userName,
			boolean tokenValid,
			PreferredUnderlying preferredUnderlying,
			String activeBroker,
			List<BrokerStatus> brokers
	) {
		public MeResponse(
				String email,
				boolean credentialsConfigured,
				boolean brokerLinked,
				String userName,
				boolean tokenValid,
				PreferredUnderlying preferredUnderlying) {
			this(email, credentialsConfigured, brokerLinked, userName, tokenValid, preferredUnderlying,
					"MSTOCK",
					List.of(new BrokerStatus(
							"MSTOCK",
							credentialsConfigured,
							brokerLinked,
							tokenValid,
							userName)));
		}
	}
}

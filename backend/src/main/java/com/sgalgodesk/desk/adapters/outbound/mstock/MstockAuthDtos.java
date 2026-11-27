package com.sgalgodesk.desk.adapters.outbound.mstock;

import jakarta.validation.constraints.NotBlank;

public final class MstockAuthDtos {

	private MstockAuthDtos() {
	}

	public record SaveApiKeyRequest(@NotBlank String apiKey) {
	}

	public record LoginRequest(@NotBlank String username, @NotBlank String password) {
	}

	public record SessionOtpRequest(@NotBlank String otp) {
	}

	public record SessionTotpRequest(@NotBlank String totp) {
	}

	public record LoginResponse(String status, String message, Object data) {
	}

	public record PrimaryIpResponse(String ip) {
	}
}

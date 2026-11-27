package com.sgalgodesk.desk.domain.shared;

public class Unauthorized extends DomainException {

	public Unauthorized(String message) {
		super(message);
	}

	public Unauthorized(String message, Throwable cause) {
		super(message, cause);
	}
}

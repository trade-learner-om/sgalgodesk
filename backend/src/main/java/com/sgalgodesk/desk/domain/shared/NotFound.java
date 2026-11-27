package com.sgalgodesk.desk.domain.shared;

public class NotFound extends DomainException {

	public NotFound(String message) {
		super(message);
	}

	public NotFound(String message, Throwable cause) {
		super(message, cause);
	}
}

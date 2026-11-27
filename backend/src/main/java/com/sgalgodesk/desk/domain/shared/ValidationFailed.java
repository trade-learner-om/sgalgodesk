package com.sgalgodesk.desk.domain.shared;

public class ValidationFailed extends DomainException {

	public ValidationFailed(String message) {
		super(message);
	}

	public ValidationFailed(String message, Throwable cause) {
		super(message, cause);
	}
}

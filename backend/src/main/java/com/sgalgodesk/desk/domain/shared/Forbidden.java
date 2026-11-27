package com.sgalgodesk.desk.domain.shared;

public class Forbidden extends DomainException {

	public Forbidden(String message) {
		super(message);
	}

	public Forbidden(String message, Throwable cause) {
		super(message, cause);
	}
}

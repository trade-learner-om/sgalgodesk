package com.sgalgodesk.desk.domain.shared;

public class Conflict extends DomainException {

	public Conflict(String message) {
		super(message);
	}

	public Conflict(String message, Throwable cause) {
		super(message, cause);
	}
}

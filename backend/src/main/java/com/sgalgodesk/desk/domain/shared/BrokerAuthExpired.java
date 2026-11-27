package com.sgalgodesk.desk.domain.shared;

public class BrokerAuthExpired extends DomainException {

	public BrokerAuthExpired(String message) {
		super(message);
	}

	public BrokerAuthExpired(String message, Throwable cause) {
		super(message, cause);
	}
}

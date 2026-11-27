package com.sgalgodesk.desk.domain.shared;

public class BrokerUnavailable extends DomainException {

	public BrokerUnavailable(String message) {
		super(message);
	}

	public BrokerUnavailable(String message, Throwable cause) {
		super(message, cause);
	}
}

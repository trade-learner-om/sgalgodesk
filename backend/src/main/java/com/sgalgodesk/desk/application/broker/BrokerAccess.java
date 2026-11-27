package com.sgalgodesk.desk.application.broker;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sgalgodesk.desk.domain.broker.BrokerCredentials;
import com.sgalgodesk.desk.domain.broker.BrokerId;
import com.sgalgodesk.desk.domain.broker.BrokerSession;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BrokerAccess {

	private final BrokerSessionService brokerSessionService;

	public record ActiveBrokerContext(
			UUID userId,
			BrokerId broker,
			String accessToken,
			String apiKeyOrClientId,
			String clientSecret) {
	}

	public Mono<ActiveBrokerContext> requireActive(UUID userId) {
		return brokerSessionService.activeBroker(userId)
				.flatMap(broker -> Mono.zip(
								brokerSessionService.requireValidSession(userId, broker),
								brokerSessionService.requireCredentials(userId, broker))
						.map(tuple -> {
							BrokerSession session = tuple.getT1();
							BrokerCredentials creds = tuple.getT2();
							return new ActiveBrokerContext(
									userId,
									broker,
									session.accessToken(),
									creds.clientId(),
									creds.clientSecret());
						}));
	}

}

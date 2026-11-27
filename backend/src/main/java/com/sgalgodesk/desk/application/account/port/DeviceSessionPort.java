package com.sgalgodesk.desk.application.account.port;

import java.util.UUID;

import com.sgalgodesk.desk.domain.account.DeviceSession;

import reactor.core.publisher.Mono;

/** Outbound port: persistence of device (cookie) sessions. */
public interface DeviceSessionPort {

	Mono<DeviceSession> findByDeviceId(UUID deviceId);

	Mono<DeviceSession> save(DeviceSession session);

	Mono<Void> deleteByDeviceId(UUID deviceId);

	Mono<Void> deleteByUserId(UUID userId);
}

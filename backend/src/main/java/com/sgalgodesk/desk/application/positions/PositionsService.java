package com.sgalgodesk.desk.application.positions;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sgalgodesk.desk.application.positions.PositionDtos.PositionsResponse;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PositionsService {

	private final PositionBookService positionBookService;

	public Mono<PositionsResponse> listPositions(UUID userId) {
		return positionBookService.snapshot(userId);
	}
}

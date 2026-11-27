package com.sgalgodesk.desk.application.indicators;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandleBackfillJob {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final CandleService candleService;

	@Scheduled(fixedDelayString = "120000", initialDelayString = "60000")
	public void backfill() {
		LocalDate today = LocalDate.now(IST);
		LocalTime now = LocalTime.now(IST);
		// NSE cash session roughly 09:15–15:30
		if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(16, 0))) {
			return;
		}
		Instant from = ZonedDateTime.of(today, LocalTime.of(9, 15), IST).toInstant();
		Instant to = Instant.now();
		candleService.backfillRecent(from, to)
				.doOnError(ex -> log.debug("Candle backfill skipped: {}", ex.toString()))
				.onErrorComplete()
				.subscribe();
	}
}

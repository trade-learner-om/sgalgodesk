package com.sgalgodesk.desk.application.dds;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Tue–Thu → current (nearest future-or-today) expiry; other weekdays → next expiry.
 */
public final class DdsExpirySelect {

	private DdsExpirySelect() {
	}

	public static LocalDate select(LocalDate today, List<LocalDate> futureExpiriesInclusive) {
		if (today == null) {
			throw new IllegalArgumentException("today required");
		}
		if (futureExpiriesInclusive == null || futureExpiriesInclusive.isEmpty()) {
			throw new IllegalStateException("No NIFTY expiries available");
		}
		List<LocalDate> sorted = futureExpiriesInclusive.stream()
				.filter(d -> d != null && !d.isBefore(today))
				.distinct()
				.sorted()
				.toList();
		if (sorted.isEmpty()) {
			throw new IllegalStateException("No NIFTY expiries on or after " + today);
		}
		DayOfWeek dow = today.getDayOfWeek();
		boolean useCurrent = dow == DayOfWeek.TUESDAY
				|| dow == DayOfWeek.WEDNESDAY
				|| dow == DayOfWeek.THURSDAY;
		if (useCurrent) {
			return sorted.getFirst();
		}
		if (sorted.size() >= 2) {
			return sorted.get(1);
		}
		return sorted.getFirst();
	}
}

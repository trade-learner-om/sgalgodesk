package com.sgalgodesk.desk.application.dds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class DdsExpirySelectTest {

	@Test
	void tuesdayUsesCurrentExpiry() {
		LocalDate tue = LocalDate.of(2026, 11, 24); // Tuesday
		List<LocalDate> expiries = List.of(
				LocalDate.of(2026, 11, 25),
				LocalDate.of(2026, 12, 2));
		assertEquals(LocalDate.of(2026, 11, 25), DdsExpirySelect.select(tue, expiries));
	}

	@Test
	void wednesdayUsesCurrentExpiry() {
		LocalDate wed = LocalDate.of(2026, 11, 25);
		List<LocalDate> expiries = List.of(
				LocalDate.of(2026, 11, 25),
				LocalDate.of(2026, 12, 2));
		assertEquals(LocalDate.of(2026, 11, 25), DdsExpirySelect.select(wed, expiries));
	}

	@Test
	void thursdayUsesCurrentExpiry() {
		LocalDate thu = LocalDate.of(2026, 11, 26);
		List<LocalDate> expiries = List.of(
				LocalDate.of(2026, 11, 27),
				LocalDate.of(2026, 12, 4));
		assertEquals(LocalDate.of(2026, 11, 27), DdsExpirySelect.select(thu, expiries));
	}

	@Test
	void mondayUsesNextExpiry() {
		LocalDate mon = LocalDate.of(2026, 11, 23); // Monday
		List<LocalDate> expiries = List.of(
				LocalDate.of(2026, 11, 25),
				LocalDate.of(2026, 12, 2));
		assertEquals(LocalDate.of(2026, 12, 2), DdsExpirySelect.select(mon, expiries));
	}

	@Test
	void fridayUsesNextExpiry() {
		LocalDate fri = LocalDate.of(2026, 11, 27);
		List<LocalDate> expiries = List.of(
				LocalDate.of(2026, 12, 2),
				LocalDate.of(2026, 12, 9));
		assertEquals(LocalDate.of(2026, 12, 9), DdsExpirySelect.select(fri, expiries));
	}

	@Test
	void emptyExpiriesThrow() {
		assertThrows(IllegalStateException.class,
				() -> DdsExpirySelect.select(LocalDate.of(2026, 11, 24), List.of()));
	}
}

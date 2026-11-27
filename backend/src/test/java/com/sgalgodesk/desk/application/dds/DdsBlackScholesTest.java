package com.sgalgodesk.desk.application.dds;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.application.dds.DdsBlackScholes.OptionKind;

class DdsBlackScholesTest {

	@Test
	void callDeltaBetweenZeroAndOne() {
		double delta = DdsBlackScholes.delta(24600, 24600, 7.0 / 365.0, 0.12, OptionKind.CE, 0);
		assertTrue(delta > 0.4 && delta < 0.6);
	}

	@Test
	void putDeltaNegative() {
		double delta = DdsBlackScholes.delta(24600, 24600, 7.0 / 365.0, 0.12, OptionKind.PE, 0);
		assertTrue(delta < -0.4 && delta > -0.6);
	}

	@Test
	void impliedVolRoundTrip() {
		double spot = 24600;
		double strike = 24700;
		double t = 10.0 / 365.0;
		double iv = 0.14;
		double price = DdsBlackScholes.price(spot, strike, t, iv, OptionKind.CE, 0);
		Double solved = DdsBlackScholes.impliedVolatility(price, spot, strike, t, OptionKind.CE, 0);
		assertNotNull(solved);
		assertTrue(Math.abs(solved - iv) < 0.01);
	}
}

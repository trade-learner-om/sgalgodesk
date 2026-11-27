package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sgalgodesk.desk.adapters.inbound.websocket.LtpTick;

/**
 * Regression: 44-byte quote OHLC starts at packet offset 28 (skip 20 after LTP), not 32.
 */
class MstockMarketFeedConnectionParseTest {

	private static final String KEY = "NFO:TEST";
	private static final int TOKEN = 47810;

	@Test
	void parsesLtpOnlyPacket() {
		LtpTick tick = parseOne(frame(packet(8, TOKEN, 12_345, null)));
		assertEquals(KEY, tick.instrumentKey());
		assertEquals(123.45, tick.ltp(), 1e-9);
	}

	@Test
	void parsesIndexQuote28() {
		byte[] body = new byte[28];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(25_000_00); // ltp
		p.putInt(25_100_00); // high
		p.putInt(24_900_00); // low
		p.putInt(24_950_00); // open
		p.putInt(24_800_00); // close
		p.putInt(200_00); // change
		LtpTick tick = parseOne(frame(body));
		assertEquals(25000.0, tick.ltp(), 1e-9);
		assertEquals(24800.0, tick.close(), 1e-9);
	}

	@Test
	void parsesEquityQuote44_openAtOffset28() {
		// Official layout: open@28 high@32 low@36 close@40 — skip 20 after LTP (not 24)
		byte[] body = new byte[44];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(15_050); // ltp 150.50
		p.putInt(1); // last qty
		p.putInt(15_000); // avg
		p.putInt(1000); // vol
		p.putInt(10); // buy
		p.putInt(20); // sell
		p.putInt(14_900); // open @28
		p.putInt(15_200); // high
		p.putInt(14_800); // low
		p.putInt(14_850); // close @40
		LtpTick tick = parseOne(frame(body));
		assertEquals(150.50, tick.ltp(), 1e-9);
		assertEquals(148.50, tick.close(), 1e-9);
	}

	@Test
	void parsesIndexPacket48() {
		byte[] body = new byte[48];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(26_000_00);
		p.putInt(26_100_00);
		p.putInt(25_900_00);
		p.putInt(25_950_00);
		p.putInt(25_800_00); // close @20
		// remaining fields unused
		LtpTick tick = parseOne(frame(body));
		assertEquals(26000.0, tick.ltp(), 1e-9);
		assertEquals(25800.0, tick.close(), 1e-9);
	}

	@Test
	void parsesFull184_sameOhlcPrefix() {
		byte[] body = new byte[184];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(99_99); // 99.99
		p.putInt(1);
		p.putInt(99_00);
		p.putInt(50);
		p.putInt(5);
		p.putInt(6);
		p.putInt(98_00); // open
		p.putInt(100_00);
		p.putInt(97_00);
		p.putInt(97_50); // close
		LtpTick tick = parseOne(frame(body));
		assertEquals(99.99, tick.ltp(), 1e-9);
		assertEquals(97.50, tick.close(), 1e-9);
	}

	@Test
	void unknownPacketSizeStillEmitsLtp() {
		byte[] body = new byte[12];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(42_00);
		p.putInt(0);
		LtpTick tick = parseOne(frame(body));
		assertEquals(42.0, tick.ltp(), 1e-9);
	}

	@Test
	void multiPacketFrame_emitsBothInOrder() {
		byte[] first = packet(8, TOKEN, 11_100, null);
		byte[] second = new byte[44];
		ByteBuffer p = ByteBuffer.wrap(second).order(ByteOrder.BIG_ENDIAN);
		p.putInt(TOKEN);
		p.putInt(22_200);
		p.putInt(1);
		p.putInt(22_000);
		p.putInt(10);
		p.putInt(1);
		p.putInt(1);
		p.putInt(21_000);
		p.putInt(23_000);
		p.putInt(20_000);
		p.putInt(20_500); // close
		byte[] frame = new byte[2 + 2 + first.length + 2 + second.length];
		ByteBuffer f = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
		f.putShort((short) 2);
		f.putShort((short) first.length);
		f.put(first);
		f.putShort((short) second.length);
		f.put(second);

		MstockMarketFeedConnection conn = connection();
		java.util.List<LtpTick> ticks = new java.util.ArrayList<>();
		var sub = conn.ticks().subscribe(ticks::add);
		conn.onBinary(frame);
		sub.dispose();
		assertEquals(2, ticks.size());
		assertEquals(111.0, ticks.get(0).ltp(), 1e-9);
		assertEquals(222.0, ticks.get(1).ltp(), 1e-9);
		assertEquals(205.0, ticks.get(1).close(), 1e-9);
	}

	private static LtpTick parseOne(byte[] frame) {
		MstockMarketFeedConnection conn = connection();
		LtpTick[] holder = new LtpTick[1];
		var sub = conn.ticks().subscribe(t -> holder[0] = t);
		conn.onBinary(frame);
		sub.dispose();
		assertNotNull(holder[0], "expected a tick");
		return holder[0];
	}

	private static MstockMarketFeedConnection connection() {
		return new MstockMarketFeedConnection(
				null,
				"wss://example.test",
				"api",
				"token",
				Map.of(String.valueOf(TOKEN), new MstockFeedToken(String.valueOf(TOKEN), KEY, 2)));
	}

	private static byte[] frame(byte[] packet) {
		ByteBuffer f = ByteBuffer.allocate(2 + 2 + packet.length).order(ByteOrder.BIG_ENDIAN);
		f.putShort((short) 1);
		f.putShort((short) packet.length);
		f.put(packet);
		return f.array();
	}

	private static byte[] packet(int size, int token, int ltpRaw, Integer closeRaw) {
		byte[] body = new byte[size];
		ByteBuffer p = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
		p.putInt(token);
		p.putInt(ltpRaw);
		if (closeRaw != null && size >= 28) {
			p.position(20);
			p.putInt(closeRaw);
		}
		return body;
	}
}

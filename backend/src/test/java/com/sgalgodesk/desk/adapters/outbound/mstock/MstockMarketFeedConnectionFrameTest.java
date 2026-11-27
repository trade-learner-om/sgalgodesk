package com.sgalgodesk.desk.adapters.outbound.mstock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Regression: MStock Type A control messages must be text frames (official SDK uses ws.send(string)).
 */
class MstockMarketFeedConnectionFrameTest {

	@Test
	void loginIsTextFrame() {
		WebSocketFrame frame = MstockMarketFeedConnection.toOutboundFrame("LOGIN:dummy-token");
		assertInstanceOf(TextWebSocketFrame.class, frame);
		assertEquals("LOGIN:dummy-token", ((TextWebSocketFrame) frame).text());
		frame.release();
	}

	@Test
	void subscribeJsonIsTextFrame() {
		String json = "{\"a\":\"subscribe\",\"v\":[47810,26000]}";
		WebSocketFrame frame = MstockMarketFeedConnection.toOutboundFrame(json);
		assertInstanceOf(TextWebSocketFrame.class, frame);
		assertEquals(json, ((TextWebSocketFrame) frame).text());
		frame.release();
	}

	@Test
	void modeJsonIsTextFrame() {
		String json = "{\"a\":\"mode\",\"v\":[\"quote\",[47810]]}";
		WebSocketFrame frame = MstockMarketFeedConnection.toOutboundFrame(json);
		assertInstanceOf(TextWebSocketFrame.class, frame);
		assertTrue(((TextWebSocketFrame) frame).text().contains("\"quote\""));
		frame.release();
	}
}

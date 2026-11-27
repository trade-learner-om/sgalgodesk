package com.sgalgodesk.desk.adapters.inbound.websocket;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import com.sgalgodesk.desk.adapters.inbound.websocket.PortfolioFeedWebSocketHandler;
import com.sgalgodesk.desk.adapters.inbound.websocket.SpreadFeedWebSocketHandler;

@Configuration
public class MarketFeedWebSocketConfig {

	@Bean
	HandlerMapping marketFeedHandlerMapping(
			MarketFeedWebSocketHandler marketHandler,
			PortfolioFeedWebSocketHandler portfolioHandler,
			SpreadFeedWebSocketHandler spreadHandler) {
		SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
		mapping.setOrder(-1);
		mapping.setUrlMap(Map.of(
				"/api/ws/market", marketHandler,
				"/api/ws/portfolio", portfolioHandler,
				"/api/ws/spread", spreadHandler));
		return mapping;
	}

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}
}

package com.sgalgodesk.desk.adapters.inbound.http;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class HttpRoutersConfig {

	@Bean
	public RouterFunction<ServerResponse> accountRouter(AccountHandlers h) {
		return RouterFunctions.route()
				.path("/api/account", b -> b
						.POST("/signup", h::signup)
						.POST("/login", h::login)
						.POST("/logout", h::logout)
						.GET("/me", h::me)
						.PUT("/settings", h::saveSettings))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> brokerMstockRouter(BrokerMstockHandlers h) {
		return RouterFunctions.route()
				.path("/api/brokers/mstock", b -> b
						.GET("/primary-ip", h::primaryIp)
						.PUT("/credentials", h::saveCredentials)
						.DELETE("/credentials", h::clearCredentials)
						.POST("/login", h::login)
						.POST("/session", h::sessionOtp)
						.POST("/session/totp", h::sessionTotp))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> instrumentsRouter(InstrumentsHandlers h) {
		return RouterFunctions.route()
				.path("/api/instruments", b -> b
						.GET("/search", h::search)
						.GET("/underlyings", h::underlyings)
						.GET("/expiries", h::expiries)
						.GET("/benchmarks", h::benchmarks)
						.GET("/tradable", h::tradable))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> optionChainRouter(OptionChainHandlers h) {
		return RouterFunctions.route()
				.path("/api/option-chain", b -> b
						.GET("", h::chain))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> quotesRouter(QuotesHandlers h) {
		return RouterFunctions.route()
				.path("/api/quotes", b -> b
						.POST("/ltp", h::ltp)
						.GET("/greeks", h::greeks))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> ordersRouter(OrdersHandlers h) {
		return RouterFunctions.route()
				.path("/api/orders", b -> b
						.POST("/place", h::place)
						.POST("/place-multi", h::placeMulti))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> positionsRouter(PositionsHandlers h) {
		return RouterFunctions.route()
				.path("/api/positions", b -> b
						.GET("", h::list)
						.POST("/exit", h::exit))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> indicatorsRouter(IndicatorsHandlers h) {
		return RouterFunctions.route()
				.GET("/api/candles", h::candles)
				.GET("/api/indicators/signal", h::signal)
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> spreadRouter(SpreadHandlers h) {
		return RouterFunctions.route()
				.path("/api/spread", b -> b
						.POST("/history", h::history))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> strategyRouter(StrategyHandlers h) {
		return RouterFunctions.route()
				.path("/api/strategies", b -> b
						.POST("/suggest-credit-spread", h::suggestCreditSpread))
				.build();
	}

	@Bean
	public RouterFunction<ServerResponse> algoStrategiesRouter(AlgoStrategiesHandlers h) {
		return RouterFunctions.route()
				.path("/api/algo-strategies", b -> b
						.GET("", h::list)
						.POST("", h::create)
						.PUT("/{id}", h::update)
						.DELETE("/{id}", h::delete)
						.POST("/{id}/run", h::run)
						.POST("/{id}/stop", h::stop)
						.GET("/{id}/lifecycle", h::lifecycle))
				.build();
	}
}

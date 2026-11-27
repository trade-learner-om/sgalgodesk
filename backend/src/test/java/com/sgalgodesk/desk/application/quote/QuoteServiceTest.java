package com.sgalgodesk.desk.application.quote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sgalgodesk.desk.adapters.outbound.mstock.MstockApiClient;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockInstrumentService;
import com.sgalgodesk.desk.adapters.outbound.mstock.MstockMarketFeedHub;
import com.sgalgodesk.desk.application.broker.BrokerAccess;
import com.sgalgodesk.desk.application.broker.BrokerSessionService;
import com.sgalgodesk.desk.application.quote.QuoteDtos.LtpResponse;
import com.sgalgodesk.desk.domain.broker.BrokerId;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

	@Mock
	MstockApiClient mstockApiClient;
	@Mock
	MstockInstrumentService mstockInstrumentService;
	@Mock
	MstockMarketFeedHub marketFeedHub;
	@Mock
	BrokerAccess brokerAccess;
	@Mock
	BrokerSessionService brokerSessionService;

	QuoteService quoteService;

	@BeforeEach
	void setUp() {
		quoteService = new QuoteService(
				mstockApiClient, mstockInstrumentService, marketFeedHub, brokerAccess, brokerSessionService);
	}

	@Test
	void ltpReadsFromLivePriceChannelNotRest() {
		UUID userId = UUID.randomUUID();
		List<String> keys = List.of("NSE:26000", "NFO:12345");
		BrokerAccess.ActiveBrokerContext ctx = new BrokerAccess.ActiveBrokerContext(
				userId, BrokerId.MSTOCK, "token", "api-key", null);

		when(brokerAccess.requireActive(userId)).thenReturn(Mono.just(ctx));
		when(brokerSessionService.translateAuthErrors(eq(userId), eq(BrokerId.MSTOCK), any()))
				.thenAnswer(inv -> inv.getArgument(2));
		when(marketFeedHub.awaitLtps(eq(userId), eq(keys), any(Duration.class)))
				.thenReturn(Mono.just(Map.of("NSE:26000", 100.5, "NFO:12345", 12.25)));

		LtpResponse res = quoteService.ltp(userId, keys).block();
		assertEquals(100.5, res.prices().get("NSE:26000"));
		assertEquals(12.25, res.prices().get("NFO:12345"));
		verify(marketFeedHub).awaitLtps(eq(userId), eq(keys), any(Duration.class));
	}

	@Test
	void emptyKeysReturnsEmptyMap() {
		UUID userId = UUID.randomUUID();
		LtpResponse res = quoteService.ltp(userId, List.of()).block();
		assertTrue(res.prices().isEmpty());
	}
}

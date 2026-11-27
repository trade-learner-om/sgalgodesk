package com.sgalgodesk.desk.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

	/**
	 * Shared outbound client using platform DNS with IPv4 preferred.
	 * Avoids bare WebClient's default dual-stack resolver that fails with
	 * "Failed to resolve … [A(1), AAAA(28)] after 4 queries" on flaky IPv6.
	 */
	@Bean
	@Qualifier("outboundHttpClient")
	HttpClient outboundHttpClient() {
		DnsAddressResolverGroup resolver = new DnsAddressResolverGroup(
				new DnsNameResolverBuilder()
						.datagramChannelType(NioDatagramChannel.class)
						.resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
						.nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
						.maxQueriesPerResolve(8)
						.queryTimeoutMillis(3_000));
		return HttpClient.create().resolver(resolver);
	}

	@Bean
	WebClient mstockWebClient(
			MstockProperties properties,
			@Qualifier("outboundHttpClient") HttpClient outboundHttpClient) {
		return WebClient.builder()
				.clientConnector(new ReactorClientHttpConnector(outboundHttpClient))
				.baseUrl(properties.apiBaseUrl())
				.exchangeStrategies(ExchangeStrategies.builder()
						.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
						.build())
				.build();
	}

	@Bean
	@Qualifier("outboundWebClient")
	WebClient outboundWebClient(@Qualifier("outboundHttpClient") HttpClient outboundHttpClient) {
		return WebClient.builder()
				.clientConnector(new ReactorClientHttpConnector(outboundHttpClient))
				.build();
	}
}

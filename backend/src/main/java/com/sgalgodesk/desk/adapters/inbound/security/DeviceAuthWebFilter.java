package com.sgalgodesk.desk.adapters.inbound.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.sgalgodesk.desk.application.account.DeviceSessionService;
import com.sgalgodesk.desk.application.account.DeviceSessionService.ResolvedSession;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Rehydrates {@link AppPrincipal} from the DEVICE_ID cookie + Redis app session
 * into the Reactor {@link org.springframework.security.core.context.SecurityContext}
 * so login survives hard refresh and server restarts until 07:59:59 IST.
 *
 * <p>No WebSession / STRATEGY_SESSION — those write Set-Cookie before WebSocket 101
 * and break Reactor Netty upgrades.
 */
@Component
@RequiredArgsConstructor
public class DeviceAuthWebFilter implements WebFilter, Ordered {

	private static final String WS_PATH_PREFIX = "/api/ws/";

	private final DeviceSessionService deviceSessionService;

	@Override
	public int getOrder() {
		// Before Spring Security's authentication filters.
		return Ordered.HIGHEST_PRECEDENCE + 50;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		var deviceId = DeviceCookieSupport.readDeviceId(exchange);
		if (deviceId == null) {
			return chain.filter(exchange);
		}
		boolean websocket = isWebSocketRequest(exchange);
		return deviceSessionService.resolve(deviceId)
				.flatMap(resolved -> continueWithAuth(exchange, chain, resolved))
				.switchIfEmpty(Mono.defer(() -> {
					// Never clear cookies on WS — Set-Cookie blocks the 101 upgrade.
					if (!websocket) {
						DeviceCookieSupport.clearDeviceId(exchange);
					}
					return chain.filter(exchange);
				}));
	}

	/** True for WebSocket upgrades — no cookie mutations allowed. */
	static boolean isWebSocketRequest(ServerWebExchange exchange) {
		String path = exchange.getRequest().getPath().pathWithinApplication().value();
		if (path != null && path.startsWith(WS_PATH_PREFIX)) {
			return true;
		}
		String upgrade = exchange.getRequest().getHeaders().getFirst(HttpHeaders.UPGRADE);
		return upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
	}

	private Mono<Void> continueWithAuth(
			ServerWebExchange exchange,
			WebFilterChain chain,
			ResolvedSession resolved) {
		AppPrincipal principal = new AppPrincipal(resolved.user().id(), resolved.user().email());
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextImpl context = new SecurityContextImpl(authentication);
		return chain.filter(exchange)
				.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)));
	}
}

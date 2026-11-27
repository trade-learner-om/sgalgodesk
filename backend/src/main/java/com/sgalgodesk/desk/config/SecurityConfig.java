package com.sgalgodesk.desk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({ MstockProperties.class, AppProperties.class, InstrumentsProperties.class })
public class SecurityConfig {

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
			CorsConfigurationSource corsConfigurationSource) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((exchange, e) -> {
							exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
							return exchange.getResponse().setComplete();
						}))
				.authorizeExchange(auth -> auth
						.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.pathMatchers("/api/account/signup", "/api/account/login").permitAll()
						.pathMatchers("/api/**").authenticated()
						.anyExchange().permitAll())
				.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties appProperties) {
		CorsConfiguration config = new CorsConfiguration();
		String origins = appProperties.corsOrigins();
		List<String> originList = Arrays.stream(origins.split(","))
				.map(String::trim)
				.filter(StringUtils::hasText)
				.toList();
		config.setAllowedOrigins(originList);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

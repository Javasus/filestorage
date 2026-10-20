package com.nosulkora.filestorage.security;

import com.nosulkora.filestorage.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;
    private final UserService userService;

    public SecurityConfig(
            JwtTokenProvider tokenProvider,
            UserService userService
    ) {
        this.tokenProvider = tokenProvider;
        this.userService = userService;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                // Кастомная обработка ошибок безопасности, что бы избежать дефолтных редиректов.
                .exceptionHandling(exceptionHandlingSpec -> exceptionHandlingSpec
                        .authenticationEntryPoint((exchange, ex) -> Mono.fromRunnable(() ->
                                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED)))
                                .accessDeniedHandler((exchange, e) -> Mono.fromRunnable(() ->
                                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN))))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/auth/**", "/swagger-ui/**", "/v3/api-docs/**")
                                .permitAll() // Открытые эндпоинты.
                        .anyExchange().authenticated()) // все остальные требуют jwt.
                // Добавляем наш JWT фильтр прямо перед стандартным фильтром аутентификации
                .addFilterBefore(new JwtTokenFilter(tokenProvider, userService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
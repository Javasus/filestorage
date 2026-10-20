package com.nosulkora.filestorage.security;

import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.service.UserService;
import io.jsonwebtoken.Claims;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class JwtTokenFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenProvider tokenProvider;
    private final UserService userService;

    public JwtTokenFilter(
            JwtTokenProvider tokenProvider,
            UserService userService
    ) {
        this.tokenProvider = tokenProvider;
        this.userService = userService;
    }

    @NotNull
    @Override
    public Mono<Void> filter(@NotNull ServerWebExchange exchange, @NotNull WebFilterChain chain) {
        String token = extractToken(exchange);

        if (token != null) {
            Optional<Claims> claimsOpt = tokenProvider.parseAndValidateToken(token);

            if (claimsOpt.isPresent()) {
                Claims claims = claimsOpt.get();

                // Достаём все нужные данные из распарсенного объекта Claims
                String username = claims.getSubject();
                Integer tokenVersionFromJwt = claims.get("tv", Integer.class);

                return userService.findSecurityInfoByUsername(username)
                        // Если пользователь не найден в БД или произошла ошибка, сразу возвращаем 403, до контроллера
                        .onErrorResume(Exception.class, e -> Mono.empty())
                        .switchIfEmpty(Mono.defer(Mono::empty))
                        .flatMap(securityInfo -> {

                            // Проверяем: если юзер заблокирован ИЛИ версия токена в БД изменилась — сбрасываем сессию
                            if (securityInfo.getStatus() == UserStatus.BLOCKED ||
                                !securityInfo.getTokenVersion().equals(tokenVersionFromJwt)) {
                                return getBlockedResponse(exchange);
                            }

                            // если все в порядке, продолжаем стандартную авторизацию
                            Integer userId = claims.get("id", Integer.class);
                            String roleStr = claims.get("role", String.class);

                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + roleStr));

                            // Создаем Principal и устанавливаем контекст
                            UserPrincipal principal = new UserPrincipal(userId, username, UserRole.valueOf(roleStr));
                            var authentication = new UsernamePasswordAuthenticationToken(
                                    principal, null, authorities);

                            return chain.filter(exchange)
                                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                                    .then(Mono.just(true));
                        })
                        .switchIfEmpty(Mono.defer(() -> getBlockedResponse(exchange)))
                        .then();
            }
        }

        return chain.filter(exchange);
    }

    private String extractToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }

        return null;
    }

    /**
     * Отправляет пользователю JSON-сообщение о блокировке.
     */
    private Mono<Void> getBlockedResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Формируем тело JSON-ответа
        String jsonBody =
                "{\"error\": \"Access Denied\", \"message\": \"Your account is blocked. Please contact support.\"}";

        DataBuffer buffer = response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}

package com.streamflix.gateway;

import com.streamflix.common.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Single authentication choke point for the platform. Validates the JWT once at the edge and
 * forwards the verified identity to downstream services as the {@code X-User-Id} /
 * {@code X-User-Email} headers, so services stay stateless and never re-validate tokens.
 *
 * <p>Client-supplied identity headers are stripped first to prevent spoofing.</p>
 *
 * <p>Public (unauthenticated) surface: user register/login and catalog browsing (GET /api/videos).
 * Everything else requires a valid token.</p>
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (isPublic(request)) {
            return chain.filter(stripIdentity(exchange));
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(exchange);
        }
        try {
            Claims claims = jwtService.parse(header.substring(7));
            String userId = claims.getSubject();
            String email = String.valueOf(claims.get("email"));
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> {
                        h.remove("X-User-Id");
                        h.remove("X-User-Email");
                        h.set("X-User-Id", userId);
                        h.set("X-User-Email", email);
                    })
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return reject(exchange);
        }
    }

    private boolean isPublic(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path.startsWith("/actuator")) {
            return true;
        }
        if (path.equals("/api/users/login") || path.equals("/api/users/register")) {
            return true;
        }
        // Browsing the catalog is open; behavioral POSTs to /api/videos still require auth.
        return request.getMethod() == HttpMethod.GET && path.startsWith("/api/videos");
    }

    private ServerWebExchange stripIdentity(ServerWebExchange exchange) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Email");
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -1; // run before routing
    }
}

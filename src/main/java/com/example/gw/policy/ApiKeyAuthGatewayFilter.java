package com.example.gw.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

@Slf4j
public class ApiKeyAuthGatewayFilter implements GatewayFilter {

    private final String header;
    private final Set<String> keys;

    public ApiKeyAuthGatewayFilter(String header, List<String> keys) {
        this.header = header;
        this.keys = Set.copyOf(keys);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(header);
        if (apiKey == null || !keys.contains(apiKey)) {
            log.warn("API key authentication failed — header '{}'", header);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        var mutatedRequest = exchange.getRequest().mutate()
                .headers(h -> h.remove(header))
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}

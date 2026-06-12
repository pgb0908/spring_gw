package com.example.gw.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class RequestContextFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        // 모든 필터보다 먼저 실행되어 RequestContext를 exchange에 심어둔다
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        var context = new RequestContext(traceId, Instant.now());
        exchange.getAttributes().put(RequestContext.ATTR_KEY, context);

        log.debug("RequestContext 생성 — traceId={}, requestedAt={}", context.getTraceId(), context.getRequestedAt());

        return chain.filter(exchange);
    }
}

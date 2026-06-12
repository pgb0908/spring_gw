package com.example.gw.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IpFilterGatewayFilterTest {

    private GatewayFilterChain mockChain() {
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    void allowList에_있는_IP는_요청을_통과시킨다() {
        var filter = new IpFilterGatewayFilter(List.of("10.0.0.0/8", "192.168.1.0/24"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .remoteAddress(new InetSocketAddress("10.0.0.5", 0))
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void allowList에_없는_IP는_403을_반환한다() {
        var filter = new IpFilterGatewayFilter(List.of("10.0.0.0/8"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .remoteAddress(new InetSocketAddress("172.16.0.1", 0))
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    void 정확한_IP_매칭도_허용한다() {
        var filter = new IpFilterGatewayFilter(List.of("192.168.1.100"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .remoteAddress(new InetSocketAddress("192.168.1.100", 0))
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void allowList가_비어있으면_모든_요청을_통과시킨다() {
        var filter = new IpFilterGatewayFilter(List.of());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }
}

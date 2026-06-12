package com.example.gw.policy;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyAuthGatewayFilterTest {

    private GatewayFilterChain mockChain() {
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    void 유효한_API_Key는_요청을_통과시킨다() {
        var filter = new ApiKeyAuthGatewayFilter("X-API-Key", List.of("valid-key-1", "valid-key-2"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("X-API-Key", "valid-key-1")
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void API_Key_헤더_없으면_401을_반환한다() {
        var filter = new ApiKeyAuthGatewayFilter("X-API-Key", List.of("valid-key"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void 유효하지_않은_API_Key는_401을_반환한다() {
        var filter = new ApiKeyAuthGatewayFilter("X-API-Key", List.of("valid-key"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("X-API-Key", "wrong-key")
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void 인증_후_API_Key_헤더를_제거하고_전달한다() {
        var filter = new ApiKeyAuthGatewayFilter("X-API-Key", List.of("valid-key"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("X-API-Key", "valid-key")
                        .header("Content-Type", "application/json")
                        .build());

        var capturedExchange = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            capturedExchange.set(inv.getArgument(0));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(capturedExchange.get().getRequest().getHeaders().containsKey("X-API-Key")).isFalse();
        assertThat(capturedExchange.get().getRequest().getHeaders().containsKey("Content-Type")).isTrue();
    }

    @Test
    void 커스텀_헤더_이름을_사용할_수_있다() {
        var filter = new ApiKeyAuthGatewayFilter("Authorization", List.of("my-key"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "my-key")
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }
}

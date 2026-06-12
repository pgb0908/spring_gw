package com.example.gw.routing;

import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class GatewayRoutingFilterTest {

    private CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub flowStub;
    private GatewayRoutingFilter filter;

    @BeforeEach
    void setUp() {
        flowStub = mock(CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub.class);
        filter = new GatewayRoutingFilter(Map.of("flow-xyz", flowStub));
    }

    // ── 동작 E: Flow 라우트는 gRPC stub을 호출한다 ────────────────────────
    @Test
    void Flow_라우트는_flowId_stub으로_StartFlow를_호출한다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.SUCCESS).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        verify(flowStub).startFlow(argThat(envelope ->
                envelope.getFlowId().equals("flow-xyz")
                        && envelope.getAction() == GatewayCoreAction.START_REQUEST));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 동작 F: Connector 라우트는 chain을 통과시킨다 ─────────────────────
    @Test
    void Connector_라우트는_chain을_통과시킨다() {
        var exchange = exchangeWithRoute("http://10.0.0.1:8080", "Connector", null);
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    // ── 동작 G: Flow 라우팅 후 setAlreadyRouted가 설정된다 ────────────────
    @Test
    void Flow_라우팅_후_setAlreadyRouted가_설정된다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.SUCCESS).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(ServerWebExchangeUtils.isAlreadyRouted(exchange)).isTrue();
    }

    // ── 동작 H: Flow gRPC 오류 응답 → HTTP 500 ───────────────────────────
    @Test
    void Flow_gRPC_ERROR_상태는_HTTP_500을_반환한다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder()
                        .setStatus(GatewayCoreStatus.ERROR)
                        .setErrorCode("INVALID")
                        .setErrorMessage("bad input")
                        .build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── 동작 I: envelope에 HTTP body와 Content-Type이 담긴다 ──────────────
    @Test
    void HTTP_body와_ContentType이_envelope_payload에_담긴다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"key\":\"value\"}"));
        Route route = Route.async()
                .id("test-route").uri(URI.create("grpc://10.0.0.3:9090")).order(0)
                .predicate(r -> true)
                .metadata("destinationKind", "Flow")
                .metadata("flowId", "flow-xyz")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.SUCCESS).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        verify(flowStub).startFlow(argThat(envelope ->
                envelope.getPayload().toStringUtf8().equals("{\"key\":\"value\"}")
                        && envelope.getContentType().contains("application/json")));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private MockServerWebExchange exchangeWithRoute(String uri, String destinationKind, String flowId) {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));
        Route.AsyncBuilder builder = Route.async()
                .id("test-route")
                .uri(URI.create(uri))
                .order(0)
                .predicate(r -> true)
                .metadata("destinationKind", destinationKind);
        if (flowId != null) {
            builder.metadata("flowId", flowId);
        }
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, builder.build());
        return exchange;
    }

    private GatewayFilterChain mockChain() {
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }
}

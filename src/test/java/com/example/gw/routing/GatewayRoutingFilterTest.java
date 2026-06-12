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
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class GatewayRoutingFilterTest {

    private CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub flowStub;
    private PendingResponseRegistry pendingResponseRegistry;
    private GatewayRoutingFilter filter;

    @BeforeEach
    void setUp() {
        flowStub = mock(CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub.class);
        pendingResponseRegistry = mock(PendingResponseRegistry.class);
        filter = new GatewayRoutingFilter(Map.of("flow-xyz", flowStub), pendingResponseRegistry);
    }

    // ── 동작 E: Flow 라우트는 StartFlow를 호출하고 SendResponse를 기다린다 ────
    @Test
    void Flow_라우트는_StartFlow_후_SendResponse_응답을_HTTP로_반환한다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        // StartFlow는 RECEIVED(수신 확인)를 즉시 반환한다
        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        // PendingResponseRegistry에 sink가 등록되면 즉시 SendResponse 응답을 주입한다
        GatewayCoreEnvelope sendResponseEnvelope = GatewayCoreEnvelope.newBuilder()
                .setGuid("test-guid")
                .setFlowId("flow-xyz")
                .setStatus(GatewayCoreStatus.SUCCESS)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"result\":\"ok\"}"))
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .build();

        doAnswer(invocation -> {
            String guid = invocation.getArgument(0);
            Sinks.One<GatewayCoreEnvelope> sink = invocation.getArgument(1);
            sink.tryEmitValue(sendResponseEnvelope);
            return null;
        }).when(pendingResponseRegistry).register(anyString(), any());

        // ReportResponseResult stub
        when(flowStub.reportResponseResult(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        verify(flowStub).startFlow(argThat(env ->
                env.getFlowId().equals("flow-xyz")
                        && env.getAction() == GatewayCoreAction.START_REQUEST));
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
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        GatewayCoreEnvelope responseEnvelope = GatewayCoreEnvelope.newBuilder()
                .setStatus(GatewayCoreStatus.SUCCESS).build();
        doAnswer(inv -> { ((Sinks.One<GatewayCoreEnvelope>) inv.getArgument(1)).tryEmitValue(responseEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());
        when(flowStub.reportResponseResult(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(ServerWebExchangeUtils.isAlreadyRouted(exchange)).isTrue();
    }

    // ── 동작 H: StartFlow가 ERROR를 반환하면 HTTP 502를 반환한다 ─────────
    @Test
    void StartFlow_ERROR_응답은_HTTP_502를_반환한다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder()
                        .setStatus(GatewayCoreStatus.ERROR)
                        .setErrorMessage("flow not found")
                        .build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(pendingResponseRegistry).error(anyString(), any());
    }

    // ── 동작 I: SendResponse ERROR는 HTTP 500을 반환한다 ─────────────────
    @Test
    void SendResponse_ERROR_상태는_HTTP_500을_반환한다() {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        GatewayCoreEnvelope errorEnvelope = GatewayCoreEnvelope.newBuilder()
                .setStatus(GatewayCoreStatus.ERROR)
                .setErrorMessage("flow execution failed")
                .build();
        doAnswer(inv -> { ((Sinks.One<GatewayCoreEnvelope>) inv.getArgument(1)).tryEmitValue(errorEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());
        when(flowStub.reportResponseResult(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(flowStub).reportResponseResult(any());
    }

    // ── 동작 J: SendResponse 후 ReportResponseResult가 호출된다 ──────────
    @Test
    void SendResponse_수신_후_ReportResponseResult가_호출된다() throws InterruptedException {
        var exchange = exchangeWithRoute("grpc://10.0.0.3:9090", "Flow", "flow-xyz");

        when(flowStub.startFlow(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        GatewayCoreEnvelope responseEnvelope = GatewayCoreEnvelope.newBuilder()
                .setGuid("g-1").setFlowId("flow-xyz").setStatus(GatewayCoreStatus.SUCCESS).build();
        doAnswer(inv -> { ((Sinks.One<GatewayCoreEnvelope>) inv.getArgument(1)).tryEmitValue(responseEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());
        when(flowStub.reportResponseResult(any())).thenReturn(
                GatewayCoreAck.newBuilder().setStatus(GatewayCoreStatus.RECEIVED).build());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        // fireReportResponseResult는 boundedElastic 스레드에서 비동기 실행된다
        Thread.sleep(100);
        verify(flowStub).reportResponseResult(argThat(env -> env.getGuid().equals("g-1")));
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

package com.example.gw.routing;

import com.example.gw.model.FlowEnvelope;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class GatewayRoutingFilterTest {

    private CoreHttpClient coreHttpClient;
    private PendingResponseRegistry pendingResponseRegistry;
    private GatewayRoutingFilter filter;

    @BeforeEach
    void setUp() {
        coreHttpClient = mock(CoreHttpClient.class);
        pendingResponseRegistry = mock(PendingResponseRegistry.class);
        filter = new GatewayRoutingFilter(coreHttpClient, pendingResponseRegistry);
    }

    // ── 동작 E: Flow 라우트는 StartFlow를 호출하고 ResponseRequest를 기다린다 ─

    @Test
    void Flow_라우트는_StartFlow_후_ResponseRequest_응답을_HTTP로_반환한다() {
        var exchange = exchangeWithRoute("http://10.0.0.3:8080", "Flow", "flow-xyz");

        FlowEnvelope ack = ack("test-guid");
        when(coreHttpClient.postStartFlow(anyString(), any())).thenReturn(Mono.just(ack));

        FlowEnvelope responseEnvelope = responseEnvelope("test-guid", "SUCCESS",
                "{\"result\":\"ok\"}", MediaType.APPLICATION_JSON_VALUE);
        doAnswer(invocation -> {
            Sinks.One<FlowEnvelope> sink = invocation.getArgument(1);
            sink.tryEmitValue(responseEnvelope);
            return null;
        }).when(pendingResponseRegistry).register(anyString(), any());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        verify(coreHttpClient).postStartFlow(eq("flow-xyz"),
                argThat(env -> "START_REQUEST".equals(env.getAction()) && "flow-xyz".equals(env.getFlowId())));
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
        var exchange = exchangeWithRoute("http://10.0.0.3:8080", "Flow", "flow-xyz");

        when(coreHttpClient.postStartFlow(anyString(), any())).thenReturn(Mono.just(ack("g-1")));
        FlowEnvelope responseEnvelope = responseEnvelope("g-1", "SUCCESS", null, null);
        doAnswer(inv -> { ((Sinks.One<FlowEnvelope>) inv.getArgument(1)).tryEmitValue(responseEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(ServerWebExchangeUtils.isAlreadyRouted(exchange)).isTrue();
    }

    // ── 동작 H: StartFlow가 ERROR를 반환하면 HTTP 502를 반환한다 ─────────

    @Test
    void StartFlow_ERROR_응답은_HTTP_502를_반환한다() {
        var exchange = exchangeWithRoute("http://10.0.0.3:8080", "Flow", "flow-xyz");

        FlowEnvelope errorAck = new FlowEnvelope();
        errorAck.setGuid("g-1");
        errorAck.setStatus("ERROR");
        errorAck.setErrorMessage("flow not found");
        when(coreHttpClient.postStartFlow(anyString(), any())).thenReturn(Mono.just(errorAck));

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(pendingResponseRegistry).error(anyString(), any());
    }

    // ── 동작 I: ResponseRequest ERROR는 HTTP 500을 반환한다 ──────────────

    @Test
    void ResponseRequest_ERROR_상태는_HTTP_500을_반환한다() {
        var exchange = exchangeWithRoute("http://10.0.0.3:8080", "Flow", "flow-xyz");

        when(coreHttpClient.postStartFlow(anyString(), any())).thenReturn(Mono.just(ack("g-1")));
        FlowEnvelope errorEnvelope = responseEnvelope("g-1", "ERROR", null, null);
        errorEnvelope.setErrorMessage("flow execution failed");
        doAnswer(inv -> { ((Sinks.One<FlowEnvelope>) inv.getArgument(1)).tryEmitValue(errorEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(coreHttpClient).postResponseAckAsync(eq("flow-xyz"), any());
    }

    // ── 동작 J: ResponseRequest 수신 후 ResponseAck가 호출된다 ────────────

    @Test
    void ResponseRequest_수신_후_ResponseAck가_호출된다() throws InterruptedException {
        var exchange = exchangeWithRoute("http://10.0.0.3:8080", "Flow", "flow-xyz");

        when(coreHttpClient.postStartFlow(anyString(), any())).thenReturn(Mono.just(ack("g-1")));
        FlowEnvelope responseEnvelope = responseEnvelope("g-1", "SUCCESS", null, null);
        doAnswer(inv -> { ((Sinks.One<FlowEnvelope>) inv.getArgument(1)).tryEmitValue(responseEnvelope); return null; })
                .when(pendingResponseRegistry).register(anyString(), any());

        StepVerifier.create(filter.filter(exchange, mockChain())).verifyComplete();

        // postResponseAckAsync는 doOnTerminate에서 실행되므로 완료 후 즉시 확인 가능
        verify(coreHttpClient).postResponseAckAsync(eq("flow-xyz"),
                argThat(env -> "g-1".equals(env.getGuid())));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private FlowEnvelope ack(String guid) {
        FlowEnvelope env = new FlowEnvelope();
        env.setGuid(guid);
        env.setStatus("RUNNING");
        env.setErrorCode("");
        env.setErrorMessage("");
        return env;
    }

    private FlowEnvelope responseEnvelope(String guid, String status, String payloadJson, String contentType) {
        FlowEnvelope env = new FlowEnvelope();
        env.setGuid(guid);
        env.setFlowId("flow-xyz");
        env.setStatus(status);
        env.setAction("RESPONSE_REQUEST");
        if (payloadJson != null) {
            env.setPayload(java.util.Base64.getEncoder().encodeToString(payloadJson.getBytes()));
        }
        env.setContentType(contentType);
        return env;
    }

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

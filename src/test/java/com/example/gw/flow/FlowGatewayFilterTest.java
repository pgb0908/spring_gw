package com.example.gw.flow;

import com.tmax.iip.common.grpc.runtime.v1.*;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FlowGatewayFilterTest {

    private GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub stub;
    private FlowGatewayFilter filter;

    @BeforeEach
    void setUp() {
        stub = mock(GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub.class);
        filter = new FlowGatewayFilter(Map.of("my-flow", stub));
    }

    // ── 동작 11: X-Flow-Id 헤더가 없으면 필터를 건너뛴다 ────────────────
    @Test
    void Flow_Id_헤더가_없으면_체인을_그대로_통과한다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/no-flow").build());
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(stub);
    }

    // ── 동작 12: Flow 목적지로 요청이 들어오면 gRPC를 호출한다 ────────────
    @Test
    void Flow_Id에_해당하는_stub으로_ExecuteFlow를_호출한다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/flow/run")
                        .header(FlowGatewayFilter.FLOW_ID_HEADER, "my-flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"key\":\"value\"}"));
        var chain = mock(GatewayFilterChain.class);

        ExecuteFlowResponse response = ExecuteFlowResponse.newBuilder()
                .setStatusCode(200)
                .setPayload(RuntimePayload.newBuilder()
                        .setBody(ByteString.copyFromUtf8("{\"result\":\"ok\"}"))
                        .setContentType(MediaType.APPLICATION_JSON_VALUE)
                        .build())
                .build();
        when(stub.executeFlow(any(ExecuteFlowRequest.class))).thenReturn(response);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(stub).executeFlow(any(ExecuteFlowRequest.class));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 동작 13: gRPC 응답의 status_code가 HTTP 응답 상태로 변환된다 ─────
    @Test
    void gRPC_응답의_status_code가_HTTP_응답_상태로_변환된다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/flow/run")
                        .header(FlowGatewayFilter.FLOW_ID_HEADER, "my-flow")
                        .build());
        var chain = mock(GatewayFilterChain.class);

        ExecuteFlowResponse response = ExecuteFlowResponse.newBuilder()
                .setStatusCode(201)
                .setPayload(RuntimePayload.newBuilder()
                        .setBody(ByteString.copyFromUtf8("created"))
                        .setContentType(MediaType.TEXT_PLAIN_VALUE)
                        .build())
                .build();
        when(stub.executeFlow(any(ExecuteFlowRequest.class))).thenReturn(response);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── 동작 14: RuntimeError가 있으면 오류 HTTP 응답을 반환한다 ─────────
    @Test
    void RuntimeError가_있으면_오류_상태로_응답한다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/flow/run")
                        .header(FlowGatewayFilter.FLOW_ID_HEADER, "my-flow")
                        .build());
        var chain = mock(GatewayFilterChain.class);

        ExecuteFlowResponse response = ExecuteFlowResponse.newBuilder()
                .setStatusCode(422)
                .setError(RuntimeError.newBuilder()
                        .setCode("VALIDATION_FAILED")
                        .setMessage("invalid input")
                        .build())
                .build();
        when(stub.executeFlow(any(ExecuteFlowRequest.class))).thenReturn(response);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── 동작 15: X-Trace-Id 헤더가 없으면 새 trace_id를 생성한다 ──────────
    @Test
    void Trace_Id_헤더가_없으면_새_UUID를_생성해서_요청에_포함한다() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/flow/run")
                        .header(FlowGatewayFilter.FLOW_ID_HEADER, "my-flow")
                        .build());
        var chain = mock(GatewayFilterChain.class);

        ExecuteFlowResponse response = ExecuteFlowResponse.newBuilder()
                .setStatusCode(200)
                .setPayload(RuntimePayload.newBuilder()
                        .setBody(ByteString.copyFromUtf8("ok"))
                        .setContentType(MediaType.TEXT_PLAIN_VALUE)
                        .build())
                .build();
        when(stub.executeFlow(any(ExecuteFlowRequest.class))).thenReturn(response);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(stub).executeFlow(argThat(req ->
                req.getHeader().getTraceId() != null && !req.getHeader().getTraceId().isBlank()
                        && req.getHeader().getRequestId() != null && !req.getHeader().getRequestId().isBlank()
        ));
    }
}

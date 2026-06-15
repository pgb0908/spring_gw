package com.example.gw;

import com.google.protobuf.ByteString;
import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0008: Gateway-Flow 비동기 요청-응답 분리 통합 테스트.
 *
 * 검증 범위:
 *   HTTP 클라이언트
 *     → Gateway GatewayRoutingFilter (StartFlow)
 *     → MockCoreRuntimeService 포트 19999
 *         ↓ SendResponse 역호출
 *     → Gateway GatewayRuntimeService 포트 19998
 *     → HTTP 응답 완료
 *     → ReportResponseResult fire-and-forget
 *
 * ADR-0008 전체 흐름:
 *   Step 1. GatewayRoutingFilter가 guid → Sinks.One을 PendingResponseRegistry에 등록한다.
 *           (StartFlow 호출 전에 등록 — race condition 방지)
 *   Step 2. CoreRuntimeService.StartFlow(GatewayCoreEnvelope) 호출 → RECEIVED ACK 즉시 반환.
 *   Step 3. responseSink.asMono() 대기.
 *   Step 4. Flow 엔진(MockCoreRuntimeService)이 GatewayRuntimeService.SendResponse()를 역호출
 *           → PendingResponseRegistry.complete(guid) → sink 방출.
 *   Step 5. HTTP 응답 작성: SendResponse payload → body, contentType → Content-Type, status → HTTP status.
 *   Step 6. CoreRuntimeService.ReportResponseResult() — HTTP 응답 전송 직후 fire-and-forget.
 *
 * 픽스처 (src/test/resources/integration/):
 *   - gateway.json        : 최소 Gateway 리소스
 *   - flow.json           : test-flow, flowId=test-flow-id, host=localhost, port=19999
 *   - router.json         : POST /test/** → test-flow (Flow 목적지)
 *   - listener-grpc.json  : protocol=GRPC, port=19998 (게이트웨이 GatewayRuntimeService 포트)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=src/test/resources/integration"
})
class FlowRoutingIntegrationTest {

    static final String FLOW_ID = "test-flow-id";
    static final int GATEWAY_GRPC_PORT = 19998; // listener-grpc.json과 일치

    // ── 테스트 인프라 ────────────────────────────────────────────────────────

    @TestConfiguration
    static class MockCoreRuntimeServerConfig {

        @Bean
        MockCoreRuntimeService mockCoreRuntimeService() {
            return new MockCoreRuntimeService();
        }

        @Bean(destroyMethod = "shutdown")
        Server coreRuntimeGrpcServer(MockCoreRuntimeService service) throws IOException {
            return ServerBuilder.forPort(19999).addService(service).build().start();
        }
    }

    /**
     * CoreRuntimeService 테스트 구현체.
     *
     * startFlow 동작은 세 가지 모드로 제어한다:
     *   SUCCESS_RESPONSE (기본값)
     *     RECEIVED ACK 반환 후, SendResponse(SUCCESS)를 게이트웨이로 역호출한다.
     *   ERROR_RESPONSE
     *     RECEIVED ACK 반환 후, SendResponse(ERROR)를 게이트웨이로 역호출한다.
     *     → 게이트웨이는 HTTP 500으로 응답하고 ReportResponseResult를 호출한다.
     *   REJECT
     *     ERROR ACK를 즉시 반환하고 SendResponse를 호출하지 않는다.
     *     → 게이트웨이는 HTTP 502로 응답한다.
     */
    @Slf4j
    static class MockCoreRuntimeService extends CoreRuntimeServiceGrpc.CoreRuntimeServiceImplBase {

        enum StartFlowMode { SUCCESS_RESPONSE, ERROR_RESPONSE, REJECT }

        private volatile StartFlowMode mode = StartFlowMode.SUCCESS_RESPONSE;
        private final AtomicReference<GatewayCoreEnvelope> lastStartFlowEnvelope = new AtomicReference<>();
        private final AtomicReference<GatewayCoreEnvelope> lastReportEnvelope    = new AtomicReference<>();

        void reset() {
            mode = StartFlowMode.SUCCESS_RESPONSE;
            lastStartFlowEnvelope.set(null);
            lastReportEnvelope.set(null);
        }

        void setMode(StartFlowMode mode) {
            this.mode = mode;
        }

        // ADR-0008 Step 2: StartFlow 수신 처리
        @Override
        public void startFlow(GatewayCoreEnvelope envelope, StreamObserver<GatewayCoreAck> responseObserver) {
            lastStartFlowEnvelope.set(envelope);
            log.info("▶ StartFlow 수신 — guid={}, flowId={}, mode={}", envelope.getGuid(), envelope.getFlowId(), mode);

            if (mode == StartFlowMode.REJECT) {
                // REJECT: ERROR ACK를 즉시 반환, SendResponse 없음 → 게이트웨이 HTTP 502
                responseObserver.onNext(GatewayCoreAck.newBuilder()
                        .setGuid(envelope.getGuid())
                        .setStatus(GatewayCoreStatus.ERROR)
                        .setErrorMessage("flow rejected")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // RECEIVED ACK 즉시 반환 (ADR-0008 Step 2)
            responseObserver.onNext(GatewayCoreAck.newBuilder()
                    .setGuid(envelope.getGuid())
                    .setStatus(GatewayCoreStatus.RECEIVED)
                    .build());
            responseObserver.onCompleted();

            // ADR-0008 Step 4: GatewayRuntimeService.SendResponse() 역호출
            callGatewaySendResponse(envelope);
        }

        // ADR-0008 Step 6: ReportResponseResult 수신 처리
        @Override
        public void reportResponseResult(GatewayCoreEnvelope envelope,
                                         StreamObserver<GatewayCoreAck> responseObserver) {
            lastReportEnvelope.set(envelope);
            log.info("▶ ReportResponseResult 수신 — guid={}", envelope.getGuid());
            responseObserver.onNext(GatewayCoreAck.newBuilder()
                    .setGuid(envelope.getGuid())
                    .setStatus(GatewayCoreStatus.RECEIVED)
                    .build());
            responseObserver.onCompleted();
        }

        // ADR-0008 Step 4: 게이트웨이의 GatewayRuntimeService.SendResponse() 역호출
        private void callGatewaySendResponse(GatewayCoreEnvelope requestEnvelope) {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress("localhost", GATEWAY_GRPC_PORT)
                    .usePlaintext()
                    .build();
            try {
                GatewayRuntimeServiceGrpc.GatewayRuntimeServiceBlockingStub stub =
                        GatewayRuntimeServiceGrpc.newBlockingStub(channel);

                GatewayCoreEnvelope response = (mode == StartFlowMode.ERROR_RESPONSE)
                        ? GatewayCoreEnvelope.newBuilder()
                                .setGuid(requestEnvelope.getGuid())
                                .setFlowId(requestEnvelope.getFlowId())
                                .setStatus(GatewayCoreStatus.ERROR)
                                .setErrorMessage("flow execution failed")
                                .build()
                        : GatewayCoreEnvelope.newBuilder()
                                .setGuid(requestEnvelope.getGuid())
                                .setFlowId(requestEnvelope.getFlowId())
                                .setStatus(GatewayCoreStatus.SUCCESS)
                                .setPayload(ByteString.copyFromUtf8("{\"result\":\"ok\"}"))
                                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                                .build();

                stub.sendResponse(response);
                log.info("◀ SendResponse 전송 완료 — guid={}, status={}", requestEnvelope.getGuid(), response.getStatus());
            } finally {
                channel.shutdown();
            }
        }

        GatewayCoreEnvelope lastStartFlowEnvelope() { return lastStartFlowEnvelope.get(); }
        GatewayCoreEnvelope lastReportEnvelope()     { return lastReportEnvelope.get(); }
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    MockCoreRuntimeService mockService;

    @BeforeEach
    void resetMock() {
        mockService.reset();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADR-0008 Step 1-2: StartFlow GatewayCoreEnvelope 필드 전파 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void StartFlow_envelope에_올바른_flowId가_전달된다() {
        post("/test/orders", "{\"order\":\"1\"}").expectStatus().isOk();

        assertThat(lastEnvelope().getFlowId()).isEqualTo(FLOW_ID);
    }

    @Test
    void StartFlow_envelope_action이_START_REQUEST이다() {
        post("/test/ping", "{}").expectStatus().isOk();

        assertThat(lastEnvelope().getAction()).isEqualTo(GatewayCoreAction.START_REQUEST);
    }

    @Test
    void HTTP_body가_StartFlow_envelope_payload로_전달된다() {
        String body = "{\"item\":\"widget\",\"qty\":5}";
        post("/test/items", body).expectStatus().isOk();

        assertThat(lastEnvelope().getPayload().toStringUtf8()).isEqualTo(body);
    }

    @Test
    void HTTP_ContentType이_StartFlow_envelope_contentType으로_전달된다() {
        post("/test/data", "{}").expectStatus().isOk();

        assertThat(lastEnvelope().getContentType()).contains("application/json");
    }

    // ── ADR-0008 Step 1: guid = RequestContext.traceId ─────────────────────

    @Test
    void X_Trace_Id_헤더가_없으면_UUID가_guid로_생성된다() {
        post("/test/ping", "{}").expectStatus().isOk();

        // UUID 형식 검증 (8-4-4-4-12)
        assertThat(lastEnvelope().getGuid())
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void X_Trace_Id_헤더값이_StartFlow_envelope_guid로_전달된다() {
        String traceId = "trace-abc-999";
        webTestClient.post().uri("/test/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(lastEnvelope().getGuid()).isEqualTo(traceId);
    }

    @Test
    void requestedAt이_StartFlow_envelope_startedAt으로_전달된다() {
        long before = System.currentTimeMillis();
        post("/test/ping", "{}").expectStatus().isOk();
        long after = System.currentTimeMillis();

        assertThat(lastEnvelope().getStartedAt())
                .isGreaterThanOrEqualTo(before)
                .isLessThanOrEqualTo(after);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADR-0008 Step 4-5: SendResponse → HTTP 응답 변환
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void SendResponse_SUCCESS_payload가_HTTP_200_body로_변환된다() {
        post("/test/response-check", "{}").expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void SendResponse_SUCCESS_contentType이_HTTP_응답_Content_Type_헤더로_전달된다() {
        post("/test/response-check", "{}").expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void SendResponse_ERROR_상태는_HTTP_500으로_변환된다() {
        mockService.setMode(MockCoreRuntimeService.StartFlowMode.ERROR_RESPONSE);

        post("/test/error-check", "{}").expectStatus().isEqualTo(500);
    }

    @Test
    void SendResponse_ERROR_errorMessage가_HTTP_응답_body로_전달된다() {
        mockService.setMode(MockCoreRuntimeService.StartFlowMode.ERROR_RESPONSE);

        post("/test/error-check", "{}").expectStatus().isEqualTo(500)
                .expectBody(String.class).isEqualTo("flow execution failed");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADR-0008 Step 6: ReportResponseResult fire-and-forget
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void HTTP_응답_완료_후_ReportResponseResult가_호출된다() throws InterruptedException {
        post("/test/report-check", "{}").expectStatus().isOk();

        // fire-and-forget — boundedElastic 스레드에서 비동기 실행되므로 완료를 짧게 대기한다
        Thread.sleep(200);

        assertThat(mockService.lastReportEnvelope())
                .as("ReportResponseResult가 호출되지 않음").isNotNull();
    }

    @Test
    void ReportResponseResult_guid가_StartFlow_guid와_일치한다() throws InterruptedException {
        String traceId = "trace-report-999";
        webTestClient.post().uri("/test/report-check")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        Thread.sleep(200);

        assertThat(mockService.lastReportEnvelope().getGuid()).isEqualTo(traceId);
    }

    @Test
    void SendResponse_ERROR_후에도_ReportResponseResult가_호출된다() throws InterruptedException {
        // writeHttpResponse는 ERROR 응답에도 doOnTerminate로 ReportResponseResult를 호출한다
        mockService.setMode(MockCoreRuntimeService.StartFlowMode.ERROR_RESPONSE);
        post("/test/report-check", "{}").expectStatus().isEqualTo(500);

        Thread.sleep(200);

        assertThat(mockService.lastReportEnvelope())
                .as("오류 응답 후 ReportResponseResult가 호출되지 않음").isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADR-0008 Step 2 실패 경로: StartFlow ERROR ACK
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void StartFlow_ERROR_ACK는_HTTP_502를_반환한다() {
        mockService.setMode(MockCoreRuntimeService.StartFlowMode.REJECT);

        post("/test/reject-check", "{}").expectStatus().isEqualTo(502);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private WebTestClient.ResponseSpec post(String uri, String body) {
        return webTestClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private GatewayCoreEnvelope lastEnvelope() {
        return mockService.lastStartFlowEnvelope();
    }
}

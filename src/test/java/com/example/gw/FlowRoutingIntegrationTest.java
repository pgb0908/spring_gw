package com.example.gw;

import com.google.protobuf.ByteString;
import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
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
 * Flow 라우팅 통합 테스트.
 *
 * 검증 범위: WebTestClient(HTTP) → Spring Cloud Gateway → mock CoreRuntimeService (포트 19999)
 *                                                            ↓ case2: SendResponse 콜백
 *                                Gateway GatewayRuntimeService (포트 19998) ← Flow 엔진 역방향 호출
 *
 * 전체 파이프라인 (case1 + case2):
 *   1. WebTestClient가 HTTP 요청을 게이트웨이로 보낸다.
 *   2. RequestContextFilter가 traceId, requestedAt을 exchange에 심는다.
 *   3. GatewayRoutingFilter가 StartFlow를 호출하고 SendResponse를 기다린다.
 *   4. MockCoreRuntimeService(포트 19999)가 StartFlow를 수신하고
 *      게이트웨이의 GatewayRuntimeService(포트 19998)로 SendResponse를 역호출한다.
 *   5. 게이트웨이가 SendResponse를 수신해 HTTP 응답을 완료하고
 *      CoreRuntimeService.ReportResponseResult()를 호출한다.
 *
 * 픽스처 (src/test/resources/integration/):
 *   - gateway.json        : 최소 Gateway 리소스
 *   - flow.json           : test-flow, flowId=test-flow-id, host=localhost, port=19999
 *   - router.json         : POST /test/** → test-flow (Flow 목적지)
 *   - listener-grpc.json  : protocol=GRPC, port=19998 (게이트웨이 gRPC 서버 포트)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=src/test/resources/integration"
})
class FlowRoutingIntegrationTest {

    static final String FLOW_ID = "test-flow-id";
    // 게이트웨이 GatewayRuntimeService 포트 (listener-grpc.json과 일치)
    static final int GATEWAY_GRPC_PORT = 19998;

    /**
     * 포트 19999에 CoreRuntimeService mock 서버를 기동한다.
     * StartFlow 수신 시 게이트웨이의 GatewayRuntimeService(포트 19998)로
     * SendResponse를 역호출해 case2 경로를 완성한다.
     */
    @TestConfiguration
    static class MockCoreRuntimeServerConfig {

        @Bean
        MockCoreRuntimeService mockCoreRuntimeService() {
            return new MockCoreRuntimeService();
        }

        @Bean(destroyMethod = "shutdown")
        Server coreRuntimeGrpcServer(MockCoreRuntimeService service) throws IOException {
            return ServerBuilder.forPort(19999)
                    .addService(service)
                    .build()
                    .start();
        }
    }

    /**
     * CoreRuntimeService 테스트 구현체.
     *
     * StartFlow 수신 시:
     *   1. GatewayCoreEnvelope를 캡처(어서션용)
     *   2. 게이트웨이의 GatewayRuntimeService.SendResponse()를 역호출 (case2 시뮬레이션)
     *   3. GatewayCoreAck{RECEIVED}를 반환
     *
     * ReportResponseResult 수신 시:
     *   1. 호출 여부 캡처(어서션용)
     *   2. GatewayCoreAck{RECEIVED}를 반환
     */
    @Slf4j
    static class MockCoreRuntimeService extends CoreRuntimeServiceGrpc.CoreRuntimeServiceImplBase {

        private final AtomicReference<GatewayCoreEnvelope> lastStartFlowEnvelope = new AtomicReference<>();
        private final AtomicReference<GatewayCoreEnvelope> lastReportEnvelope = new AtomicReference<>();

        @Override
        public void startFlow(GatewayCoreEnvelope envelope, StreamObserver<GatewayCoreAck> responseObserver) {
            lastStartFlowEnvelope.set(envelope);

            log.info("▶ StartFlow 수신");
            log.info("  guid        = {}", envelope.getGuid());
            log.info("  flowId      = {}", envelope.getFlowId());
            log.info("  startedAt   = {}", envelope.getStartedAt());
            log.info("  action      = {}", envelope.getAction());
            log.info("  contentType = {}", envelope.getContentType());
            log.info("  body        = {}", envelope.getPayload().toStringUtf8());

            // case2: 게이트웨이의 GatewayRuntimeService로 SendResponse 역호출
            callGatewaySendResponse(envelope);

            responseObserver.onNext(GatewayCoreAck.newBuilder()
                    .setGuid(envelope.getGuid())
                    .setStatus(GatewayCoreStatus.RECEIVED)
                    .build());
            responseObserver.onCompleted();
        }

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

        /**
         * 게이트웨이의 GatewayRuntimeService.SendResponse()를 호출해
         * 플로우 실행 결과를 게이트웨이에 전달한다.
         */
        private void callGatewaySendResponse(GatewayCoreEnvelope requestEnvelope) {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress("localhost", GATEWAY_GRPC_PORT)
                    .usePlaintext()
                    .build();
            try {
                GatewayRuntimeServiceGrpc.GatewayRuntimeServiceBlockingStub stub =
                        GatewayRuntimeServiceGrpc.newBlockingStub(channel);

                GatewayCoreEnvelope responseEnvelope = GatewayCoreEnvelope.newBuilder()
                        .setGuid(requestEnvelope.getGuid())
                        .setFlowId(requestEnvelope.getFlowId())
                        .setStatus(GatewayCoreStatus.SUCCESS)
                        .setPayload(ByteString.copyFromUtf8("{\"result\":\"ok\"}"))
                        .setContentType(MediaType.APPLICATION_JSON_VALUE)
                        .build();

                stub.sendResponse(responseEnvelope);
                log.info("◀ SendResponse 전송 완료 — guid={}", requestEnvelope.getGuid());
            } finally {
                channel.shutdown();
            }
        }

        GatewayCoreEnvelope lastStartFlowEnvelope() {
            return lastStartFlowEnvelope.get();
        }

        GatewayCoreEnvelope lastReportEnvelope() {
            return lastReportEnvelope.get();
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    MockCoreRuntimeService mockCoreRuntimeService;

    // ── 라우팅 검증 ─────────────────────────────────────────────────────────────

    @Test
    void HTTP_요청이_Flow로_라우팅될_때_올바른_flowId로_StartFlow가_호출된다() {
        webTestClient.post()
                .uri("/test/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"order\":\"123\"}")
                .exchange()
                .expectStatus().isOk();

        GatewayCoreEnvelope envelope = mockCoreRuntimeService.lastStartFlowEnvelope();
        assertThat(envelope).as("gRPC StartFlow가 호출되지 않음 — Flow 라우팅 실패").isNotNull();
        assertThat(envelope.getFlowId()).isEqualTo(FLOW_ID);
        assertThat(envelope.getAction()).isEqualTo(GatewayCoreAction.START_REQUEST);
    }

    // ── GatewayCoreEnvelope 필드 검증 ────────────────────────────────────────────

    @Test
    void HTTP_body가_envelope_payload로_전달된다() {
        String requestBody = "{\"item\":\"widget\",\"qty\":5}";

        webTestClient.post()
                .uri("/test/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk();

        assertThat(mockCoreRuntimeService.lastStartFlowEnvelope().getPayload().toStringUtf8())
                .isEqualTo(requestBody);
    }

    @Test
    void HTTP_Content_Type이_envelope_contentType으로_전달된다() {
        webTestClient.post()
                .uri("/test/data")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(mockCoreRuntimeService.lastStartFlowEnvelope().getContentType())
                .contains("application/json");
    }

    // ── RequestContext 전파 검증 ──────────────────────────────────────────────────

    @Test
    void RequestContextFilter가_생성한_traceId가_envelope_guid로_전달된다() {
        webTestClient.post()
                .uri("/test/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(mockCoreRuntimeService.lastStartFlowEnvelope().getGuid()).isNotBlank();
    }

    @Test
    void X_Trace_Id_헤더가_있으면_그_값이_envelope_guid로_전달된다() {
        String traceId = "trace-abc-999";

        webTestClient.post()
                .uri("/test/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(mockCoreRuntimeService.lastStartFlowEnvelope().getGuid()).isEqualTo(traceId);
    }

    @Test
    void requestedAt이_envelope_startedAt으로_전달된다() {
        long before = System.currentTimeMillis();

        webTestClient.post()
                .uri("/test/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(mockCoreRuntimeService.lastStartFlowEnvelope().getStartedAt())
                .isGreaterThanOrEqualTo(before);
    }

    // ── case2: SendResponse → HTTP 응답 변환 + ReportResponseResult ──────────────

    @Test
    void SendResponse_body가_HTTP_응답_body로_변환된다() {
        // MockCoreRuntimeService가 SendResponse로 {"result":"ok"}를 전송한다
        webTestClient.post()
                .uri("/test/response-check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void HTTP_응답_완료_후_ReportResponseResult가_호출된다() throws InterruptedException {
        webTestClient.post()
                .uri("/test/report-check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        // ReportResponseResult는 HTTP 응답 직후 별도 스레드에서 fire-and-forget으로 실행된다.
        // 로컬 gRPC 호출이므로 수십 ms 안에 완료되지만, 단언 전 완료를 보장하기 위해 짧게 대기한다.
        Thread.sleep(200);

        assertThat(mockCoreRuntimeService.lastReportEnvelope())
                .as("ReportResponseResult가 호출되지 않음").isNotNull();
        assertThat(mockCoreRuntimeService.lastReportEnvelope().getGuid())
                .isEqualTo(mockCoreRuntimeService.lastStartFlowEnvelope().getGuid());
    }
}

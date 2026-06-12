package com.example.gw;

import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
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
 *
 * 전체 파이프라인:
 *   1. WebTestClient가 HTTP 요청을 게이트웨이로 보낸다.
 *   2. RequestContextFilter가 traceId, requestedAt을 exchange에 심는다.
 *   3. GatewayRoutingFilter가 destinationKind=Flow를 감지하고
 *      FlowGrpcChannelConfig가 flow.json에서 만든 채널(localhost:19999)로 StartFlow를 호출한다.
 *   4. MockCoreRuntimeService(포트 19999)가 GatewayCoreEnvelope를 수신해 컨텍스트를 로그로 출력한다.
 *
 * flowStubs bean을 교체하지 않으므로 FlowGrpcChannelConfig가 flow.json을 그대로 읽어
 * localhost:19999 채널을 생성한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=src/test/resources/integration"
})
class FlowRoutingIntegrationTest {

    static final String FLOW_ID = "test-flow-id";

    /**
     * 포트 19999에 실제 gRPC 서버를 기동한다.
     * FlowGrpcChannelConfig가 flow.json(host=localhost, port=19999)을 읽어 채널을 만들므로
     * flowStubs bean 교체 없이 전체 config 경로가 그대로 동작한다.
     */
    @TestConfiguration
    static class GrpcServerConfig {

        @Bean
        MockCoreRuntimeService mockCoreRuntimeService() {
            return new MockCoreRuntimeService();
        }

        @Bean(destroyMethod = "shutdown")
        Server grpcServer(MockCoreRuntimeService service) throws IOException {
            return ServerBuilder.forPort(19999)
                    .addService(service)
                    .build()
                    .start();
        }
    }

    /**
     * CoreRuntimeService 테스트 구현체.
     * StartFlow 호출 시 수신한 GatewayCoreEnvelope의 컨텍스트를 로그로 출력하고 SUCCESS 응답을 반환한다.
     */
    @Slf4j
    static class MockCoreRuntimeService extends CoreRuntimeServiceGrpc.CoreRuntimeServiceImplBase {

        private final AtomicReference<GatewayCoreEnvelope> lastEnvelope = new AtomicReference<>();

        @Override
        public void startFlow(GatewayCoreEnvelope envelope, StreamObserver<GatewayCoreAck> responseObserver) {
            lastEnvelope.set(envelope);

            // ── 수신된 요청 컨텍스트 로그 출력 ──────────────────────────────
            log.info("▶ StartFlow 수신");
            log.info("  guid        = {}", envelope.getGuid());
            log.info("  flowId      = {}", envelope.getFlowId());
            log.info("  startedAt   = {}", envelope.getStartedAt());
            log.info("  action      = {}", envelope.getAction());
            log.info("  contentType = {}", envelope.getContentType());
            log.info("  body        = {}", envelope.getPayload().toStringUtf8());
            // ────────────────────────────────────────────────────────────────

            responseObserver.onNext(GatewayCoreAck.newBuilder()
                    .setGuid(envelope.getGuid())
                    .setStatus(GatewayCoreStatus.SUCCESS)
                    .build());
            responseObserver.onCompleted();
        }

        GatewayCoreEnvelope lastEnvelope() {
            return lastEnvelope.get();
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

        GatewayCoreEnvelope envelope = mockCoreRuntimeService.lastEnvelope();
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

        assertThat(mockCoreRuntimeService.lastEnvelope().getPayload().toStringUtf8())
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

        assertThat(mockCoreRuntimeService.lastEnvelope().getContentType())
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

        // RequestContextFilter가 생성한 traceId가 GatewayCoreEnvelope.guid에 담겨야 한다
        assertThat(mockCoreRuntimeService.lastEnvelope().getGuid()).isNotBlank();
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

        assertThat(mockCoreRuntimeService.lastEnvelope().getGuid()).isEqualTo(traceId);
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

        long startedAt = mockCoreRuntimeService.lastEnvelope().getStartedAt();
        assertThat(startedAt).isGreaterThanOrEqualTo(before);
    }
}

package com.example.gw;

import com.example.gw.model.FlowEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0008: Gateway-Flow 비동기 요청-응답 분리 통합 테스트 (HTTP 기반).
 *
 * 검증 흐름:
 *   HTTP 클라이언트
 *     → GatewayRoutingFilter — POST /core/flows/start → MockCore (port 18999)
 *         ↓ RUNNING ACK 즉시 반환
 *         ↓ POST /gateway/ingress/response → GW Egress Listener (port 18888)
 *     → HTTP 응답 완료
 *     → POST /core/flows/response-ack → MockCore (fire-and-forget)
 *
 * 픽스처 (src/test/resources/integration/):
 *   - gateway.json        : Gateway 리소스
 *   - flow.json           : test-flow, flowId=test-flow-id, host=localhost, port=18999
 *   - router.json         : POST /test/** → test-flow (Flow 목적지)
 *   - listener-egress.json: protocol=HTTP, role=EGRESS, port=18888
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=src/test/resources/integration"
})
class FlowRoutingIntegrationTest {

    static final String FLOW_ID = "test-flow-id";
    static final int MOCK_CORE_PORT = 18999;
    static final int EGRESS_PORT = 18888;

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final AtomicReference<FlowEnvelope> lastStartFlowEnvelope = new AtomicReference<>();
    static final AtomicReference<FlowEnvelope> lastResponseAckEnvelope = new AtomicReference<>();
    static final AtomicReference<String> currentMode = new AtomicReference<>("SUCCESS_RESPONSE");

    static DisposableServer mockCore;

    @BeforeAll
    static void startMockCore() {
        mockCore = HttpServer.create()
                .port(MOCK_CORE_PORT)
                .route(routes -> {
                    routes.post("/core/flows/start", FlowRoutingIntegrationTest::handleStartFlow);
                    routes.post("/core/flows/response-ack", FlowRoutingIntegrationTest::handleResponseAck);
                })
                .bindNow();
        log.info("MockCore HTTP 서버 기동 — port={}", MOCK_CORE_PORT);
    }

    @AfterAll
    static void stopMockCore() {
        if (mockCore != null) mockCore.dispose();
    }

    @BeforeEach
    void reset() {
        lastStartFlowEnvelope.set(null);
        lastResponseAckEnvelope.set(null);
        currentMode.set("SUCCESS_RESPONSE");
    }

    @Autowired
    WebTestClient webTestClient;

    // ══════════════════════════════════════════════════════════════════════
    // StartFlow FlowEnvelope 필드 전파 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void StartFlow_envelope에_올바른_flowId가_전달된다() {
        post("/test/orders", "{\"order\":\"1\"}").expectStatus().isOk();

        assertThat(lastStartFlowEnvelope.get().getFlowId()).isEqualTo(FLOW_ID);
    }

    @Test
    void StartFlow_envelope_action이_START_REQUEST이다() {
        post("/test/ping", "{}").expectStatus().isOk();

        assertThat(lastStartFlowEnvelope.get().getAction()).isEqualTo("START_REQUEST");
    }

    @Test
    void HTTP_body가_StartFlow_envelope_payload로_base64_인코딩되어_전달된다() {
        String body = "{\"item\":\"widget\",\"qty\":5}";
        post("/test/items", body).expectStatus().isOk();

        String decoded = new String(Base64.getDecoder().decode(lastStartFlowEnvelope.get().getPayload()));
        assertThat(decoded).isEqualTo(body);
    }

    @Test
    void HTTP_ContentType이_StartFlow_envelope_contentType으로_전달된다() {
        post("/test/data", "{}").expectStatus().isOk();

        assertThat(lastStartFlowEnvelope.get().getContentType()).contains("application/json");
    }

    @Test
    void X_Trace_Id_헤더가_없으면_UUID가_guid로_생성된다() {
        post("/test/ping", "{}").expectStatus().isOk();

        assertThat(lastStartFlowEnvelope.get().getGuid())
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

        assertThat(lastStartFlowEnvelope.get().getGuid()).isEqualTo(traceId);
    }

    @Test
    void requestedAt이_StartFlow_envelope_startedAt으로_전달된다() {
        long before = System.currentTimeMillis();
        post("/test/ping", "{}").expectStatus().isOk();
        long after = System.currentTimeMillis();

        assertThat(lastStartFlowEnvelope.get().getStartedAt())
                .isGreaterThanOrEqualTo(before)
                .isLessThanOrEqualTo(after);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ResponseRequest → HTTP 응답 변환
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void ResponseRequest_SUCCESS_payload가_HTTP_200_body로_변환된다() {
        post("/test/response-check", "{}").expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void ResponseRequest_SUCCESS_contentType이_HTTP_응답_Content_Type_헤더로_전달된다() {
        post("/test/response-check", "{}").expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void ResponseRequest_ERROR_상태는_HTTP_500으로_변환된다() {
        currentMode.set("ERROR_RESPONSE");
        post("/test/error-check", "{}").expectStatus().isEqualTo(500);
    }

    @Test
    void ResponseRequest_ERROR_errorMessage가_HTTP_응답_body로_전달된다() {
        currentMode.set("ERROR_RESPONSE");
        post("/test/error-check", "{}").expectStatus().isEqualTo(500)
                .expectBody(String.class).isEqualTo("flow execution failed");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ResponseAck fire-and-forget 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void HTTP_응답_완료_후_ResponseAck가_호출된다() throws InterruptedException {
        post("/test/report-check", "{}").expectStatus().isOk();

        Thread.sleep(300);

        assertThat(lastResponseAckEnvelope.get())
                .as("ResponseAck가 호출되지 않음").isNotNull();
    }

    @Test
    void ResponseAck_guid가_StartFlow_guid와_일치한다() throws InterruptedException {
        String traceId = "trace-report-999";
        webTestClient.post().uri("/test/report-check")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", traceId)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        Thread.sleep(300);

        assertThat(lastResponseAckEnvelope.get().getGuid()).isEqualTo(traceId);
    }

    @Test
    void ResponseRequest_ERROR_후에도_ResponseAck가_호출된다() throws InterruptedException {
        currentMode.set("ERROR_RESPONSE");
        post("/test/report-check", "{}").expectStatus().isEqualTo(500);

        Thread.sleep(300);

        assertThat(lastResponseAckEnvelope.get())
                .as("오류 응답 후 ResponseAck가 호출되지 않음").isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // StartFlow ERROR ACK → HTTP 502
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void StartFlow_ERROR_ACK는_HTTP_502를_반환한다() {
        currentMode.set("REJECT");
        post("/test/reject-check", "{}").expectStatus().isEqualTo(502);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private WebTestClient.ResponseSpec post(String uri, String body) {
        return webTestClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    // ── Mock Core 핸들러 ──────────────────────────────────────────────────

    private static Mono<Void> handleStartFlow(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asString()
                .flatMap(json -> parseAndRespond(json, res));
    }

    private static Mono<Void> parseAndRespond(String json, HttpServerResponse res) {
        FlowEnvelope received;
        try {
            received = MAPPER.readValue(json, FlowEnvelope.class);
        } catch (Exception e) {
            log.error("StartFlow 파싱 실패: {}", e.getMessage());
            return res.status(400).sendString(Mono.just("parse error")).then();
        }
        lastStartFlowEnvelope.set(received);
        log.info("▶ StartFlow 수신 — guid={}, mode={}", received.getGuid(), currentMode.get());

        FlowEnvelope ack = buildAck(received.getGuid());

        if ("REJECT".equals(currentMode.get())) {
            ack.setStatus("ERROR");
            ack.setErrorMessage("flow rejected");
            return sendJson(res, ack);
        }

        ack.setStatus("RUNNING");
        FlowEnvelope capturedReceived = received;
        return sendJson(res, ack)
                .then(Mono.<Void>fromRunnable(() -> callIngressResponse(capturedReceived))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static Mono<Void> handleResponseAck(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asString()
                .doOnNext(json -> {
                    try {
                        lastResponseAckEnvelope.set(MAPPER.readValue(json, FlowEnvelope.class));
                        log.info("▶ ResponseAck 수신 — guid={}", lastResponseAckEnvelope.get().getGuid());
                    } catch (Exception e) {
                        log.error("ResponseAck 파싱 실패: {}", e.getMessage());
                    }
                })
                .then(sendJson(res, buildAck("ack")));
    }

    private static Mono<Void> sendJson(HttpServerResponse res, FlowEnvelope envelope) {
        try {
            String json = MAPPER.writeValueAsString(envelope);
            return res.status(200)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just(json))
                    .then();
        } catch (Exception e) {
            return res.status(500).sendString(Mono.just("serialize error")).then();
        }
    }

    private static FlowEnvelope buildAck(String guid) {
        FlowEnvelope ack = new FlowEnvelope();
        ack.setGuid(guid);
        ack.setStatus("RUNNING");
        ack.setErrorCode("");
        ack.setErrorMessage("");
        return ack;
    }

    private static void callIngressResponse(FlowEnvelope received) {
        FlowEnvelope responseEnvelope = new FlowEnvelope();
        responseEnvelope.setGuid(received.getGuid());
        responseEnvelope.setFlowId(received.getFlowId());
        responseEnvelope.setAction("RESPONSE_REQUEST");

        if ("ERROR_RESPONSE".equals(currentMode.get())) {
            responseEnvelope.setStatus("ERROR");
            responseEnvelope.setErrorMessage("flow execution failed");
        } else {
            responseEnvelope.setStatus("RUNNING");
            responseEnvelope.setPayload(
                    Base64.getEncoder().encodeToString("{\"result\":\"ok\"}".getBytes()));
            responseEnvelope.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }

        try {
            String json = MAPPER.writeValueAsString(responseEnvelope);
            org.springframework.web.reactive.function.client.WebClient
                    .create("http://localhost:" + EGRESS_PORT)
                    .post()
                    .uri("/gateway/ingress/response")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(json)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
            log.info("◀ ResponseRequest 전송 완료 — guid={}", received.getGuid());
        } catch (Exception e) {
            log.error("ResponseRequest 전송 실패 — guid={}: {}", received.getGuid(), e.getMessage());
        }
    }
}

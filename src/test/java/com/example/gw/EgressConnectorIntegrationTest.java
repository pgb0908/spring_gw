package com.example.gw;

import com.example.gw.egress.ConnectorEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0009: Egress Connector 비동기 HTTP 패턴 통합 테스트.
 *
 * 검증 범위:
 *   Flow 엔진
 *     → POST /gateway/connector/request (Egress Listener port 19997)
 *         ↓ 즉시 RUNNING ACK
 *     → 외부 백엔드 HTTP 호출 (mock port 19996)
 *     → POST /gateway/connector/response (mock Flow core port 19995)
 *
 * 픽스처 (src/test/resources/integration-egress/):
 *   - gateway.json        : Gateway 리소스
 *   - listener-egress.json: protocol=HTTP, role=EGRESS, port=19997
 *   - connector.json      : mock-backend, host=localhost:19996
 *   - flow.json           : core-id=core-01, host=localhost:19995
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=src/test/resources/integration-egress"
})
class EgressConnectorIntegrationTest {

    static final int EGRESS_PORT = 19997;
    static final int MOCK_BACKEND_PORT = 19996;
    static final int MOCK_CORE_PORT = 19995;

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final AtomicReference<byte[]> lastBackendBody = new AtomicReference<>();
    static final AtomicReference<String> lastBackendCustomHeader = new AtomicReference<>();
    static final AtomicReference<ConnectorEnvelope> lastCoreCallback = new AtomicReference<>();

    static DisposableServer mockBackend;
    static DisposableServer mockCore;

    @BeforeAll
    static void startMockServers() {
        mockBackend = HttpServer.create()
                .port(MOCK_BACKEND_PORT)
                .route(routes -> routes.post("/**", (req, res) -> {
                    lastBackendCustomHeader.set(req.requestHeaders().get("X-Custom-Header"));
                    return req.receive().aggregate().asByteArray()
                            .doOnNext(lastBackendBody::set)
                            .then(res.status(200)
                                    .header("Content-Type", "application/json")
                                    .header("X-Backend-Result", "ok")
                                    .sendString(Mono.just("{\"backend\":\"response\"}"))
                                    .then());
                }))
                .bindNow();

        mockCore = HttpServer.create()
                .port(MOCK_CORE_PORT)
                .route(routes -> routes.post("/gateway/connector/response", (req, res) -> {
                    return req.receive().aggregate().asString()
                            .doOnNext(json -> {
                                try {
                                    lastCoreCallback.set(MAPPER.readValue(json, ConnectorEnvelope.class));
                                } catch (Exception e) {
                                    log.error("콜백 파싱 실패: {}", e.getMessage());
                                }
                            })
                            .then(res.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"guid\":\"test\",\"status\":\"RUNNING\"}"))
                                    .then());
                }))
                .bindNow();
    }

    @AfterAll
    static void stopMockServers() {
        if (mockBackend != null) mockBackend.dispose();
        if (mockCore != null) mockCore.dispose();
    }

    @BeforeEach
    void reset() {
        lastBackendBody.set(null);
        lastBackendCustomHeader.set(null);
        lastCoreCallback.set(null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ACK 즉시 응답 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void CONNECTOR_REQUEST_수신_즉시_RUNNING_ACK_반환() throws Exception {
        ConnectorEnvelope ack = sendRequest(buildRequest("test-guid-001", "core-01", null));

        assertThat(ack.getGuid()).isEqualTo("test-guid-001");
        assertThat(ack.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void ACK_에는_error_code와_error_message가_빈_값() throws Exception {
        ConnectorEnvelope ack = sendRequest(buildRequest("test-guid-002", "core-01", null));

        assertThat(ack.getErrorCode()).isEmpty();
        assertThat(ack.getErrorMessage()).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 백엔드 호출 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void payload가_base64_디코딩되어_백엔드에_전달된다() throws Exception {
        String originalBody = "{\"order\":{\"id\":\"test\"}}";
        String encodedPayload = Base64.getEncoder().encodeToString(originalBody.getBytes());

        sendRequest(buildRequest("test-guid-003", "core-01", encodedPayload));

        Thread.sleep(300);

        assertThat(lastBackendBody.get()).isNotNull();
        assertThat(new String(lastBackendBody.get())).isEqualTo(originalBody);
    }

    @Test
    void header_맵이_HTTP_요청_헤더로_백엔드에_전달된다() throws Exception {
        ConnectorEnvelope req = buildRequest("test-guid-004", "core-01", null);
        req.setHeader(Map.of("X-Custom-Header", "custom-value"));

        sendRequest(req);

        Thread.sleep(300);

        assertThat(lastBackendCustomHeader.get()).isEqualTo("custom-value");
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONNECTOR_RESPONSE 콜백 검증
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void 백엔드_성공_응답이_CONNECTOR_RESPONSE로_Flow에_전달된다() throws Exception {
        sendRequest(buildRequest("test-guid-005", "core-01", null));

        Thread.sleep(300);

        ConnectorEnvelope callback = lastCoreCallback.get();
        assertThat(callback).isNotNull();
        assertThat(callback.getStatus()).isEqualTo("RUNNING");
        assertThat(callback.getAction()).isEqualTo("CONNECTOR_RESPONSE");
    }

    @Test
    void CONNECTOR_RESPONSE_guid가_요청과_일치한다() throws Exception {
        sendRequest(buildRequest("test-guid-006", "core-01", null));

        Thread.sleep(300);

        assertThat(lastCoreCallback.get().getGuid()).isEqualTo("test-guid-006");
    }

    @Test
    void CONNECTOR_RESPONSE_payload가_백엔드_응답_본문의_base64_인코딩이다() throws Exception {
        sendRequest(buildRequest("test-guid-007", "core-01", null));

        Thread.sleep(300);

        String decodedPayload = new String(Base64.getDecoder().decode(lastCoreCallback.get().getPayload()));
        assertThat(decodedPayload).isEqualTo("{\"backend\":\"response\"}");
    }

    @Test
    void CONNECTOR_RESPONSE_gateway_id가_채워진다() throws Exception {
        sendRequest(buildRequest("test-guid-008", "core-01", null));

        Thread.sleep(300);

        assertThat(lastCoreCallback.get().getGatewayId()).isNotBlank();
    }

    @Test
    void CONNECTOR_RESPONSE_finished_at이_채워진다() throws Exception {
        long before = System.currentTimeMillis();
        sendRequest(buildRequest("test-guid-009", "core-01", null));

        Thread.sleep(300);

        long after = System.currentTimeMillis();
        Long finishedAt = lastCoreCallback.get().getFinishedAt();
        assertThat(finishedAt).isGreaterThanOrEqualTo(before).isLessThanOrEqualTo(after);
    }

    @Test
    void CONNECTOR_RESPONSE_백엔드_응답_헤더가_header_맵에_포함된다() throws Exception {
        sendRequest(buildRequest("test-guid-010", "core-01", null));

        Thread.sleep(300);

        assertThat(lastCoreCallback.get().getHeader()).containsKey("X-Backend-Result");
    }

    @Test
    void CONNECTOR_RESPONSE_core_id_에코() throws Exception {
        sendRequest(buildRequest("test-guid-011", "core-01", null));

        Thread.sleep(300);

        assertThat(lastCoreCallback.get().getCoreId()).isEqualTo("core-01");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private ConnectorEnvelope buildRequest(String guid, String coreId, String payload) {
        ConnectorEnvelope req = new ConnectorEnvelope();
        req.setGuid(guid);
        req.setStatus("RUNNING");
        req.setFlowId("flow-test");
        req.setFlowVersion(1);
        req.setCoreId(coreId);
        req.setStartedAt(System.currentTimeMillis());
        req.setIngressGatewayId("gw_http_in_order");
        req.setAction("CONNECTOR_REQUEST");
        req.setPayload(payload);
        return req;
    }

    private ConnectorEnvelope sendRequest(ConnectorEnvelope req) throws Exception {
        String reqJson = MAPPER.writeValueAsString(req);
        String responseJson = WebClient.create("http://localhost:" + EGRESS_PORT)
                .post()
                .uri("/gateway/connector/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(reqJson)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));
        return MAPPER.readValue(responseJson, ConnectorEnvelope.class);
    }
}

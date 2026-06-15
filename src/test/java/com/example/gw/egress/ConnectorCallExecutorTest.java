package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorCallExecutorTest {

    private DisposableServer mockBackend;
    private AtomicReference<byte[]> capturedBody;
    private AtomicReference<String> capturedCustomHeader;
    private int mockBackendStatus;
    private String mockBackendResponseBody;

    private ConnectorCallExecutor executor;

    @BeforeEach
    void setUp() {
        capturedBody = new AtomicReference<>();
        capturedCustomHeader = new AtomicReference<>();
        mockBackendStatus = 200;
        mockBackendResponseBody = "{\"result\":\"ok\"}";

        mockBackend = HttpServer.create().port(0)
                .route(r -> r.post("/**", (req, res) -> {
                    capturedCustomHeader.set(req.requestHeaders().get("X-Custom"));
                    return req.receive().aggregate().asByteArray()
                            .doOnNext(capturedBody::set)
                            .then(res.status(mockBackendStatus)
                                    .header("Content-Type", "application/json")
                                    .header("X-Backend-Result", "ok")
                                    .sendString(Mono.just(mockBackendResponseBody))
                                    .then());
                }))
                .bindNow();

        executor = new ConnectorCallExecutor(WebClient.builder().build());
    }

    @AfterEach
    void tearDown() {
        mockBackend.dispose();
    }

    @Test
    void 백엔드_200_응답시_CONNECTOR_RESPONSE_status_RUNNING을_반환한다() {
        FlowEnvelope request = buildRequest(null);

        FlowEnvelope response = executor.execute(backendUrl(), "", "POST", request)
                .block(Duration.ofSeconds(3));

        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getAction()).isEqualTo("CONNECTOR_RESPONSE");
    }

    @Test
    void 백엔드_응답_본문이_base64로_인코딩되어_payload에_담긴다() {
        FlowEnvelope request = buildRequest(null);

        FlowEnvelope response = executor.execute(backendUrl(), "", "POST", request)
                .block(Duration.ofSeconds(3));

        String decoded = new String(Base64.getDecoder().decode(response.getPayload()));
        assertThat(decoded).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void 요청_header_맵이_백엔드_HTTP_헤더로_전달된다() {
        FlowEnvelope request = buildRequest(null);
        request.setHeader(java.util.Map.of("X-Custom", "my-value"));

        executor.execute(backendUrl(), "", "POST", request).block(Duration.ofSeconds(3));

        assertThat(capturedCustomHeader.get()).isEqualTo("my-value");
    }

    @Test
    void 백엔드_응답_헤더가_FlowEnvelope_header_맵에_포함된다() {
        FlowEnvelope request = buildRequest(null);

        FlowEnvelope response = executor.execute(backendUrl(), "", "POST", request)
                .block(Duration.ofSeconds(3));

        assertThat(response.getHeader()).containsKey("X-Backend-Result");
    }

    @Test
    void 백엔드_5xx_응답시_status_ERROR를_반환한다() {
        mockBackendStatus = 500;
        FlowEnvelope request = buildRequest(null);

        FlowEnvelope response = executor.execute(backendUrl(), "", "POST", request)
                .block(Duration.ofSeconds(3));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getErrorCode()).isEqualTo("BACKEND_ERROR");
    }

    @Test
    void base64_인코딩된_payload가_디코딩되어_백엔드에_전달된다() {
        String originalBody = "{\"order\":\"test\"}";
        FlowEnvelope request = buildRequest(Base64.getEncoder().encodeToString(originalBody.getBytes()));

        executor.execute(backendUrl(), "", "POST", request).block(Duration.ofSeconds(3));

        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).isEqualTo(originalBody);
    }

    @Test
    void guid가_응답에_유지된다() {
        FlowEnvelope request = buildRequest(null);

        FlowEnvelope response = executor.execute(backendUrl(), "", "POST", request)
                .block(Duration.ofSeconds(3));

        assertThat(response.getGuid()).isEqualTo("test-guid");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private FlowEnvelope buildRequest(String payload) {
        FlowEnvelope req = new FlowEnvelope();
        req.setGuid("test-guid");
        req.setFlowId("flow-1");
        req.setCoreId("core-01");
        req.setAction("CONNECTOR_REQUEST");
        req.setPayload(payload);
        return req;
    }

    private String backendUrl() {
        return "http://localhost:" + mockBackend.port() + "/api";
    }
}

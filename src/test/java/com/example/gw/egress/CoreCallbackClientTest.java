package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CoreCallbackClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DisposableServer mockCore;
    private AtomicReference<String> lastBody;
    private CoreCallbackClient client;

    @BeforeEach
    void setUp() {
        lastBody = new AtomicReference<>();
        mockCore = HttpServer.create().port(0)
                .route(r -> r.post("/gateway/connector/response", (req, res) ->
                        req.receive().aggregate().asString()
                                .doOnNext(lastBody::set)
                                .then(res.status(200)
                                        .sendString(Mono.just("{}"))
                                        .then())))
                .bindNow();

        String baseUrl = "http://localhost:" + mockCore.port();
        client = new CoreCallbackClient(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                MAPPER,
                Map.of("core-01", baseUrl));
    }

    @AfterEach
    void tearDown() {
        mockCore.dispose();
    }

    @Test
    void 등록된_coreId로_postResponse_호출시_Core_콜백_URL로_POST_요청을_보낸다() throws Exception {
        FlowEnvelope response = new FlowEnvelope();
        response.setGuid("g-1");
        response.setCoreId("core-01");
        response.setAction("CONNECTOR_RESPONSE");
        response.setStatus("RUNNING");

        client.postResponse(response).block(java.time.Duration.ofSeconds(3));

        assertThat(lastBody.get()).contains("\"guid\":\"g-1\"");
        assertThat(lastBody.get()).contains("\"action\":\"CONNECTOR_RESPONSE\"");
    }

    @Test
    void 알_수_없는_coreId는_빈_Mono를_반환하고_전송하지_않는다() {
        FlowEnvelope response = new FlowEnvelope();
        response.setGuid("g-x");
        response.setCoreId("unknown-core");

        client.postResponse(response).block(java.time.Duration.ofSeconds(3));

        assertThat(lastBody.get()).isNull();
    }

    @Test
    void StandaloneConfigLoader_없이_Map으로만_생성_가능하다() {
        CoreCallbackClient c = new CoreCallbackClient(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                MAPPER,
                Map.of("core-a", "http://localhost:9999"));
        assertThat(c).isNotNull();
    }
}

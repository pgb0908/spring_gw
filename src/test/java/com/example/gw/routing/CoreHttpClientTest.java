package com.example.gw.routing;

import com.example.gw.model.FlowEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CoreHttpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DisposableServer mockCore;
    private AtomicReference<String> lastBody;
    private CoreHttpClient client;

    @BeforeEach
    void setUp() {
        lastBody = new AtomicReference<>();
        mockCore = HttpServer.create().port(0)
                .route(r -> r.post("/core/flows/start", (req, res) ->
                        req.receive().aggregate().asString()
                                .doOnNext(lastBody::set)
                                .then(res.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just(
                                                "{\"guid\":\"g-1\",\"status\":\"RUNNING\",\"error_code\":\"\",\"error_message\":\"\"}"))
                                        .then())))
                .bindNow();

        String baseUrl = "http://localhost:" + mockCore.port();
        client = new CoreHttpClient(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                MAPPER,
                Map.of("my-flow", baseUrl));
    }

    @AfterEach
    void tearDown() {
        mockCore.dispose();
    }

    @Test
    void 등록된_flowId로_postStartFlow_호출시_Core_URL로_POST_요청을_보낸다() throws Exception {
        FlowEnvelope envelope = new FlowEnvelope();
        envelope.setGuid("g-1");
        envelope.setFlowId("my-flow");

        FlowEnvelope ack = client.postStartFlow("my-flow", envelope)
                .block(java.time.Duration.ofSeconds(3));

        assertThat(ack).isNotNull();
        assertThat(ack.getStatus()).isEqualTo("RUNNING");
        assertThat(lastBody.get()).contains("\"guid\":\"g-1\"");
    }

    @Test
    void 알_수_없는_flowId로_postStartFlow_호출시_에러를_반환한다() {
        FlowEnvelope envelope = new FlowEnvelope();
        envelope.setGuid("g-x");

        StepVerifier.create(client.postStartFlow("unknown", envelope))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("unknown"))
                .verify(java.time.Duration.ofSeconds(3));
    }

    @Test
    void StandaloneConfigLoader_없이_Map으로만_생성_가능하다() {
        CoreHttpClient c = new CoreHttpClient(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                MAPPER,
                Map.of("flow-a", "http://localhost:9999"));
        assertThat(c).isNotNull();
    }
}

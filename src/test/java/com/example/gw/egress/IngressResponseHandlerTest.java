package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import com.example.gw.routing.PendingResponseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IngressResponseHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PendingResponseRegistry registry;
    private DisposableServer server;
    private WebClient client;

    @BeforeEach
    void setUp() {
        registry = mock(PendingResponseRegistry.class);
        IngressResponseHandler handler = new IngressResponseHandler(registry, MAPPER);
        server = HttpServer.create().port(0)
                .route(r -> r.post("/gateway/ingress/response", handler::handle))
                .bindNow();
        client = WebClient.create("http://localhost:" + server.port());
    }

    @AfterEach
    void tearDown() {
        server.dispose();
    }

    @Test
    void 유효한_envelope_수신시_registry_complete를_호출하고_200_ACK를_반환한다() throws Exception {
        FlowEnvelope envelope = new FlowEnvelope();
        envelope.setGuid("test-guid");
        envelope.setFlowId("flow-1");
        envelope.setStatus("RUNNING");

        String responseBody = post(MAPPER.writeValueAsString(envelope));

        assertThat(responseBody).contains("\"guid\":\"test-guid\"");
        assertThat(responseBody).contains("\"status\":\"RUNNING\"");
        verify(registry).complete(eq("test-guid"), any(FlowEnvelope.class));
    }

    @Test
    void 잘못된_JSON_수신시_registry_호출_없이_400을_반환한다() {
        int status = postStatus("not-valid-json{{{");

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(registry);
    }

    @Test
    void guid가_없는_envelope_수신시_registry_호출_없이_400을_반환한다() throws Exception {
        FlowEnvelope envelope = new FlowEnvelope();
        // guid null

        int status = postStatus(MAPPER.writeValueAsString(envelope));

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(registry);
    }

    @Test
    void guid가_빈_문자열인_envelope_수신시_400을_반환한다() throws Exception {
        FlowEnvelope envelope = new FlowEnvelope();
        envelope.setGuid("   ");

        int status = postStatus(MAPPER.writeValueAsString(envelope));

        assertThat(status).isEqualTo(400);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private String post(String json) {
        return client.post().uri("/gateway/ingress/response")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(3));
    }

    private int postStatus(String json) {
        return client.post().uri("/gateway/ingress/response")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchangeToMono(r -> r.toEntity(String.class))
                .map(r -> r.getStatusCode().value())
                .block(Duration.ofSeconds(3));
    }
}

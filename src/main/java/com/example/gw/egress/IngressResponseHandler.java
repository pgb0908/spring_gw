package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import com.example.gw.routing.PendingResponseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class IngressResponseHandler {

    private final PendingResponseRegistry pendingResponseRegistry;
    private final ObjectMapper objectMapper;

    public Publisher<Void> handle(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asString()
                .flatMap(json -> parseAndDispatch(json, res));
    }

    private Mono<Void> parseAndDispatch(String json, HttpServerResponse res) {
        FlowEnvelope envelope;
        try {
            envelope = objectMapper.readValue(json, FlowEnvelope.class);
        } catch (Exception e) {
            log.error("ResponseRequest 파싱 실패: {}", e.getMessage());
            return res.status(400)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"status\":\"ERROR\",\"error_message\":\"parse error\"}"))
                    .then();
        }

        String guid = envelope.getGuid();
        if (guid == null || guid.isBlank()) {
            log.warn("ResponseRequest guid 없음");
            return res.status(400)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"status\":\"ERROR\",\"error_message\":\"guid required\"}"))
                    .then();
        }

        pendingResponseRegistry.complete(guid, envelope);
        log.debug("ResponseRequest 처리 완료 — guid={}", guid);

        String ack = "{\"guid\":\"" + guid + "\",\"status\":\"RUNNING\",\"error_code\":\"\",\"error_message\":\"\"}";
        return res.status(200)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(ack))
                .then();
    }
}

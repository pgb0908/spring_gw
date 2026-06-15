package com.example.gw.routing;

import com.example.gw.model.FlowEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Slf4j
public class CoreHttpClient {

    private static final String START_FLOW_PATH = "/core/flows/start";
    private static final String RESPONSE_ACK_PATH = "/core/flows/response-ack";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> flowUrlMap;

    public CoreHttpClient(WebClient.Builder webClientBuilder,
                          ObjectMapper objectMapper,
                          Map<String, String> flowUrlMap) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.flowUrlMap = flowUrlMap;
    }

    public Mono<FlowEnvelope> postStartFlow(String flowId, FlowEnvelope envelope) {
        String baseUrl = flowUrlMap.get(flowId);
        if (baseUrl == null) {
            return Mono.error(new IllegalStateException("flow_id '" + flowId + "' 에 등록된 Core URL 없음"));
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("StartFlow 직렬화 실패", e));
        }

        return webClient.post()
                .uri(baseUrl + START_FLOW_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .retrieve()
                .bodyToMono(FlowEnvelope.class)
                .doOnSuccess(ack -> log.debug("StartFlow ACK 수신 — guid={}, status={}", ack.getGuid(), ack.getStatus()))
                .doOnError(e -> log.error("StartFlow 실패 — flowId={}: {}", flowId, e.getMessage()));
    }

    public void postResponseAckAsync(String flowId, FlowEnvelope envelope) {
        String baseUrl = flowUrlMap.get(flowId);
        if (baseUrl == null) {
            log.warn("ResponseAck 전송 불가 — flow_id '{}' URL 없음 (guid={})", flowId, envelope.getGuid());
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("ResponseAck 직렬화 실패 — guid={}: {}", envelope.getGuid(), e.getMessage());
            return;
        }

        Mono.fromCallable(() -> json)
                .flatMap(body -> webClient.post()
                        .uri(baseUrl + RESPONSE_ACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        r -> log.debug("ResponseAck 전송 완료 — guid={}", envelope.getGuid()),
                        e -> log.error("ResponseAck 전송 실패 — guid={}: {}", envelope.getGuid(), e.getMessage())
                );
    }
}

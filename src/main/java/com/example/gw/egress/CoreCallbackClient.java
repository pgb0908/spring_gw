package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
public class CoreCallbackClient {

    private static final String CALLBACK_PATH = "/gateway/connector/response";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> coreUrlMap;

    public CoreCallbackClient(WebClient.Builder webClientBuilder,
                              ObjectMapper objectMapper,
                              Map<String, String> coreUrlMap) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.coreUrlMap = coreUrlMap;
    }

    public Mono<Void> postResponse(FlowEnvelope response) {
        String coreId = response.getCoreId();
        String baseUrl = (coreId != null && !coreId.isBlank()) ? coreUrlMap.get(coreId) : null;
        if (baseUrl == null) {
            log.warn("core_id '{}' 에 대한 콜백 URL 없음 — CONNECTOR_RESPONSE 전송 불가 (guid={})",
                    coreId, response.getGuid());
            return Mono.empty();
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("CONNECTOR_RESPONSE 직렬화 실패 — guid={}: {}", response.getGuid(), e.getMessage());
            return Mono.empty();
        }

        return webClient.post()
                .uri(baseUrl + CALLBACK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.debug("CONNECTOR_RESPONSE 전송 완료 — guid={}", response.getGuid()))
                .doOnError(e -> log.error("CONNECTOR_RESPONSE 전송 실패 — guid={}: {}", response.getGuid(), e.getMessage()))
                .then();
    }
}

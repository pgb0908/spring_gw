package com.example.gw.egress;

import com.example.gw.standalone.StandaloneConfigLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class CoreCallbackClient {

    private static final String CALLBACK_PATH = "/gateway/connector/response";

    private final WebClient webClient;
    private final StandaloneConfigLoader configLoader;
    private final ObjectMapper objectMapper;

    public CoreCallbackClient(WebClient.Builder webClientBuilder,
                              StandaloneConfigLoader configLoader,
                              ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.configLoader = configLoader;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> postResponse(ConnectorEnvelope response) {
        String url = resolveCoreCallbackUrl(response.getCoreId());
        if (url == null) {
            log.warn("core_id '{}' 에 대한 콜백 URL 없음 — CONNECTOR_RESPONSE 전송 불가 (guid={})",
                    response.getCoreId(), response.getGuid());
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
                .uri(url + CALLBACK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.debug("CONNECTOR_RESPONSE 전송 완료 — guid={}", response.getGuid()))
                .doOnError(e -> log.error("CONNECTOR_RESPONSE 전송 실패 — guid={}: {}", response.getGuid(), e.getMessage()))
                .then();
    }

    private String resolveCoreCallbackUrl(String coreId) {
        if (coreId == null || coreId.isBlank()) return null;
        return configLoader.getConfig().getFlows().values().stream()
                .flatMap(f -> f.getSpec().getLoadBalancing().getTargets().stream())
                .filter(t -> coreId.equals(t.getCoreId()))
                .findFirst()
                .map(t -> "http://" + t.getHost() + ":" + t.getPort())
                .orElse(null);
    }
}

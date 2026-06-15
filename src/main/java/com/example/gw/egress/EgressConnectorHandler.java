package com.example.gw.egress;

import com.example.gw.model.ConnectorResource;
import com.example.gw.standalone.StandaloneConfigLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class EgressConnectorHandler {

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final CoreCallbackClient coreCallbackClient;
    private final StandaloneConfigLoader configLoader;

    public EgressConnectorHandler(ObjectMapper objectMapper,
                                  WebClient.Builder webClientBuilder,
                                  CoreCallbackClient coreCallbackClient,
                                  StandaloneConfigLoader configLoader) {
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
        this.coreCallbackClient = coreCallbackClient;
        this.configLoader = configLoader;
    }

    public Publisher<Void> handleRequest(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asByteArray()
                .flatMap(bytes -> {
                    ConnectorEnvelope envelope;
                    try {
                        envelope = objectMapper.readValue(bytes, ConnectorEnvelope.class);
                    } catch (Exception e) {
                        log.error("CONNECTOR_REQUEST 파싱 실패: {}", e.getMessage());
                        return res.status(400).send();
                    }

                    log.info("▶ CONNECTOR_REQUEST 수신 — guid={}, coreId={}, connectorId={}",
                            envelope.getGuid(), envelope.getCoreId(), envelope.getConnectorId());

                    String ackJson;
                    try {
                        ackJson = objectMapper.writeValueAsString(buildAck(envelope));
                    } catch (JsonProcessingException e) {
                        log.error("ACK 직렬화 실패: {}", e.getMessage());
                        return res.status(500).send();
                    }

                    final ConnectorEnvelope finalEnvelope = envelope;
                    return res.header("Content-Type", "application/json")
                            .sendString(Mono.just(ackJson))
                            .then()
                            .doOnTerminate(() -> processAsync(finalEnvelope));
                });
    }

    private void processAsync(ConnectorEnvelope req) {
        Mono.defer(() -> {
            byte[] payloadBytes = decodePayload(req.getPayload());
            String backendUrl = resolveBackendUrl(req.getConnectorId());
            if (backendUrl == null) {
                log.error("백엔드 URL 없음 — connector_id={}, guid={}", req.getConnectorId(), req.getGuid());
                return Mono.empty();
            }

            return webClient.post()
                    .uri(backendUrl)
                    .headers(h -> {
                        if (req.getHeader() != null) req.getHeader().forEach(h::set);
                    })
                    .bodyValue(payloadBytes)
                    .exchangeToMono(response -> response.toEntity(byte[].class))
                    .flatMap(entity -> {
                        ConnectorEnvelope callbackEnvelope = buildResponse(req, entity.getStatusCode().value(),
                                entity.getBody(), entity.getHeaders().toSingleValueMap());
                        return coreCallbackClient.postResponse(callbackEnvelope);
                    });
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
                null,
                e -> log.error("Egress 처리 실패 — guid={}: {}", req.getGuid(), e.getMessage())
        );
    }

    private ConnectorEnvelope buildAck(ConnectorEnvelope req) {
        ConnectorEnvelope ack = new ConnectorEnvelope();
        ack.setGuid(req.getGuid());
        ack.setStatus("RUNNING");
        ack.setErrorCode("");
        ack.setErrorMessage("");
        return ack;
    }

    private ConnectorEnvelope buildResponse(ConnectorEnvelope req, int httpStatus,
                                            byte[] body, Map<String, String> responseHeaders) {
        ConnectorEnvelope resp = new ConnectorEnvelope();
        // 요청에서 에코
        resp.setGuid(req.getGuid());
        resp.setFlowId(req.getFlowId());
        resp.setFlowVersion(req.getFlowVersion());
        resp.setCoreId(req.getCoreId());
        resp.setIngressGatewayId(req.getIngressGatewayId());
        resp.setNodeId(req.getNodeId());
        resp.setNodeType(req.getNodeType());
        resp.setStartedAt(req.getStartedAt());
        // GW가 채움
        resp.setGatewayId(resolveGatewayId());
        resp.setConnectorId(req.getConnectorId());
        resp.setFinishedAt(System.currentTimeMillis());
        resp.setAction("CONNECTOR_RESPONSE");

        if (httpStatus >= 200 && httpStatus < 300) {
            resp.setStatus("RUNNING");
            byte[] responseBody = body != null ? body : new byte[0];
            resp.setPayload(Base64.getEncoder().encodeToString(responseBody));
            Map<String, String> filteredHeaders = new HashMap<>(responseHeaders);
            resp.setHeader(filteredHeaders);
        } else {
            resp.setStatus("ERROR");
            resp.setErrorCode("BACKEND_ERROR");
            resp.setErrorMessage("Backend returned HTTP " + httpStatus);
        }

        return resp;
    }

    private byte[] decodePayload(String payload) {
        if (payload == null || payload.isBlank()) return new byte[0];
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            log.warn("payload base64 디코딩 실패 — raw bytes로 처리");
            return payload.getBytes();
        }
    }

    private String resolveBackendUrl(String connectorId) {
        ConnectorResource connector;
        if (connectorId != null && !connectorId.isBlank()) {
            connector = configLoader.getConfig().getConnectors().get(connectorId);
        } else {
            connector = configLoader.getConfig().getConnectors().values().stream().findFirst().orElse(null);
        }
        if (connector == null) return null;

        List<ConnectorResource.Target> targets = connector.getSpec().getLoadBalancing().getTargets();
        if (targets.isEmpty()) return null;

        ConnectorResource.Target target = targets.get(0);
        String scheme = "HTTPS".equalsIgnoreCase(connector.getSpec().getProtocol()) ? "https" : "http";
        return scheme + "://" + target.getHost() + ":" + target.getPort();
    }

    private String resolveGatewayId() {
        var gw = configLoader.getConfig().getGateway();
        return (gw != null && gw.getMetadata() != null) ? gw.getMetadata().getName() : "unknown";
    }
}

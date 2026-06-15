package com.example.gw.egress;

import com.example.gw.model.ConnectorResource;
import com.example.gw.model.FlowEnvelope;
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

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class EgressConnectorHandler {

    private final ObjectMapper objectMapper;
    private final ConnectorCallExecutor executor;
    private final CoreCallbackClient coreCallbackClient;
    private final StandaloneConfigLoader configLoader;

    public EgressConnectorHandler(ObjectMapper objectMapper,
                                  WebClient.Builder webClientBuilder,
                                  CoreCallbackClient coreCallbackClient,
                                  StandaloneConfigLoader configLoader) {
        this.objectMapper = objectMapper;
        this.executor = new ConnectorCallExecutor(webClientBuilder.build());
        this.coreCallbackClient = coreCallbackClient;
        this.configLoader = configLoader;
    }

    public Publisher<Void> handleRequest(HttpServerRequest req, HttpServerResponse res) {
        return req.receive().aggregate().asByteArray()
                .flatMap(bytes -> {
                    FlowEnvelope envelope;
                    try {
                        envelope = objectMapper.readValue(bytes, FlowEnvelope.class);
                    } catch (Exception e) {
                        log.error("CONNECTOR_REQUEST 파싱 실패: {}", e.getMessage());
                        return res.status(400).send();
                    }

                    log.info("▶ CONNECTOR_REQUEST 수신 — guid={}, coreId={}", envelope.getGuid(), envelope.getCoreId());

                    String ackJson;
                    try {
                        ackJson = objectMapper.writeValueAsString(buildAck(envelope));
                    } catch (JsonProcessingException e) {
                        log.error("ACK 직렬화 실패: {}", e.getMessage());
                        return res.status(500).send();
                    }

                    final FlowEnvelope finalEnvelope = envelope;
                    return res.header("Content-Type", "application/json")
                            .sendString(Mono.just(ackJson))
                            .then()
                            .doOnTerminate(() -> processAsync(finalEnvelope));
                });
    }

    private void processAsync(FlowEnvelope req) {
        String backendUrl = resolveBackendUrl(req.getConnectorId());
        if (backendUrl == null) {
            log.error("백엔드 URL 없음 — connector_id={}, guid={}", req.getConnectorId(), req.getGuid());
            return;
        }

        FlowEnvelope enriched = enrichWithGatewayId(req);
        executor.execute(backendUrl, enriched)
                .flatMap(coreCallbackClient::postResponse)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        e -> log.error("Egress 처리 실패 — guid={}: {}", req.getGuid(), e.getMessage())
                );
    }

    private FlowEnvelope enrichWithGatewayId(FlowEnvelope req) {
        req.setGatewayId(resolveGatewayId());
        return req;
    }

    private FlowEnvelope buildAck(FlowEnvelope req) {
        FlowEnvelope ack = new FlowEnvelope();
        ack.setGuid(req.getGuid());
        ack.setStatus("RUNNING");
        ack.setErrorCode("");
        ack.setErrorMessage("");
        return ack;
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

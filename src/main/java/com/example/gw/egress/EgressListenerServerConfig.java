package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import com.example.gw.model.ListenerResource;
import com.example.gw.routing.PendingResponseRegistry;
import com.example.gw.standalone.StandaloneConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class EgressListenerServerConfig implements InitializingBean, DisposableBean {

    private final StandaloneConfigLoader configLoader;
    private final EgressConnectorHandler connectorHandler;
    private final PendingResponseRegistry pendingResponseRegistry;
    private final ObjectMapper objectMapper;

    private DisposableServer server;

    @Override
    public void afterPropertiesSet() {
        var egressListener = configLoader.getConfig().getListeners().stream()
                .filter(l -> l.getSpec().getRole() == ListenerResource.Role.EGRESS
                          && (l.getSpec().getProtocol() == ListenerResource.Protocol.HTTP
                           || l.getSpec().getProtocol() == ListenerResource.Protocol.HTTPS))
                .findFirst();

        if (egressListener.isEmpty()) {
            log.info("EGRESS HTTP Listener 없음 — Egress 서버 미기동");
            return;
        }

        int port = egressListener.get().getSpec().getPort();
        server = HttpServer.create()
                .port(port)
                .route(routes -> routes
                        .post("/gateway/connector/request", connectorHandler::handleRequest)
                        .post("/gateway/ingress/response", this::handleIngressResponse))
                .bindNow();

        log.info("Egress HTTP 서버 기동 — port={}", port);
    }

    private reactor.core.publisher.Mono<Void> handleIngressResponse(
            reactor.netty.http.server.HttpServerRequest req,
            reactor.netty.http.server.HttpServerResponse res) {
        return req.receive().aggregate().asString()
                .flatMap(json -> {
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
                });
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.dispose();
            log.info("Egress HTTP 서버 종료");
        }
    }
}

package com.example.gw.egress;

import com.example.gw.model.ListenerResource;
import com.example.gw.standalone.StandaloneConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class EgressListenerServerConfig implements InitializingBean, DisposableBean {

    private final StandaloneConfigLoader configLoader;
    private final EgressConnectorHandler connectorHandler;
    private final IngressResponseHandler ingressResponseHandler;

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
                        .post("/gateway/ingress/response", ingressResponseHandler::handle))
                .bindNow();

        log.info("Egress HTTP 서버 기동 — port={}", port);
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.dispose();
            log.info("Egress HTTP 서버 종료");
        }
    }
}

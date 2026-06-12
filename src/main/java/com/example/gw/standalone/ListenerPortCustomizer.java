package com.example.gw.standalone;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class ListenerPortCustomizer implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    private final StandaloneConfigLoader loader;

    public ListenerPortCustomizer(StandaloneConfigLoader loader) {
        this.loader = loader;
    }

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        var listeners = loader.getConfig().getListeners();
        if (listeners.isEmpty()) {
            log.warn("Listener config 없음 — 기본 server.port 사용");
            return;
        }

        // HTTP/HTTPS Listener만 Netty 포트로 적용한다. GRPC Listener는 GatewayGrpcServerConfig가 처리한다.
        var httpListener = listeners.stream()
                .filter(l -> l.getSpec().getProtocol() == com.example.gw.model.ListenerResource.Protocol.HTTP
                          || l.getSpec().getProtocol() == com.example.gw.model.ListenerResource.Protocol.HTTPS)
                .findFirst();

        if (httpListener.isEmpty()) {
            log.warn("HTTP/HTTPS Listener 없음 — 기본 server.port 사용");
            return;
        }

        var listener = httpListener.get();
        log.info("HTTP Listener '{}' 적용 — port={}", listener.getMetadata().getName(), listener.getSpec().getPort());
        factory.setPort(listener.getSpec().getPort());
    }
}

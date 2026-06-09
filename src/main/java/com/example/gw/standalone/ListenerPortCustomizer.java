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
            log.warn("No Listener config found — using default server.port");
            return;
        }
        var listener = listeners.get(0);
        log.info("Applying Listener '{}': port={}, protocol={}",
                listener.getMetadata().getName(), listener.getSpec().getPort(), listener.getSpec().getProtocol());
        factory.setPort(listener.getSpec().getPort());
    }
}

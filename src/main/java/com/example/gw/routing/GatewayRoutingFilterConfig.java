package com.example.gw.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class GatewayRoutingFilterConfig {

    @Bean
    public GatewayRoutingFilter gatewayRoutingFilter(
            CoreHttpClient coreHttpClient,
            PendingResponseRegistry pendingResponseRegistry) {
        return new GatewayRoutingFilter(coreHttpClient, pendingResponseRegistry);
    }
}

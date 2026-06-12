package com.example.gw.routing;

import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.CoreRuntimeServiceGrpc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class GatewayRoutingFilterConfig {

    @Bean
    public GatewayRoutingFilter gatewayRoutingFilter(
            Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs) {
        return new GatewayRoutingFilter(flowStubs);
    }
}

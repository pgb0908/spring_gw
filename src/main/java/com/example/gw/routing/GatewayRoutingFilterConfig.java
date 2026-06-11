package com.example.gw.routing;

import com.tmax.iip.common.grpc.runtime.v1.GatewayCoreServiceGrpc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class GatewayRoutingFilterConfig {

    @Bean
    public GatewayRoutingFilter gatewayRoutingFilter(
            Map<String, GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub> flowStubs) {
        return new GatewayRoutingFilter(flowStubs);
    }
}

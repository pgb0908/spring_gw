package com.example.gw.standalone;

import com.example.gw.model.*;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class LoadedConfig {
    List<ListenerResource> listeners;
    GatewayResource gateway;
    List<RouterResource> routers;
    Map<String, ConnectorResource> connectors;
    Map<String, FlowResource> flows;
    List<PolicyResource> policies;

    public static LoadedConfig empty() {
        return LoadedConfig.builder()
                .listeners(List.of())
                .gateway(null)
                .routers(List.of())
                .connectors(Map.of())
                .flows(Map.of())
                .policies(List.of())
                .build();
    }
}

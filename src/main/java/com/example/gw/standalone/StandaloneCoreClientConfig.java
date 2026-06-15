package com.example.gw.standalone;

import com.example.gw.egress.ConnectorCallExecutor;
import com.example.gw.egress.CoreCallbackClient;
import com.example.gw.routing.CoreHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class StandaloneCoreClientConfig {

    @Bean
    public CoreHttpClient coreHttpClient(WebClient.Builder webClientBuilder,
                                         ObjectMapper objectMapper,
                                         StandaloneConfigLoader configLoader) {
        Map<String, String> flowUrlMap = configLoader.getConfig().getFlows().values().stream()
                .flatMap(flow -> flow.getSpec().getLoadBalancing().getTargets().stream()
                        .filter(t -> t.getFlowId() != null && !t.getFlowId().isBlank())
                        .map(t -> Map.entry(t.getFlowId(), "http://" + t.getHost() + ":" + t.getPort())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        log.debug("CoreHttpClient flowUrlMap 구성 — {}개 flow", flowUrlMap.size());
        return new CoreHttpClient(webClientBuilder, objectMapper, flowUrlMap);
    }

    @Bean
    public CoreCallbackClient coreCallbackClient(WebClient.Builder webClientBuilder,
                                                  ObjectMapper objectMapper,
                                                  StandaloneConfigLoader configLoader) {
        Map<String, String> coreUrlMap = configLoader.getConfig().getFlows().values().stream()
                .flatMap(flow -> flow.getSpec().getLoadBalancing().getTargets().stream()
                        .filter(t -> t.getCoreId() != null && !t.getCoreId().isBlank())
                        .map(t -> Map.entry(t.getCoreId(), "http://" + t.getHost() + ":" + t.getPort())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        log.debug("CoreCallbackClient coreUrlMap 구성 — {}개 core", coreUrlMap.size());
        return new CoreCallbackClient(webClientBuilder, objectMapper, coreUrlMap);
    }
}

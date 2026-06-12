package com.example.gw.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class IpFilterGatewayFilterFactory extends AbstractGatewayFilterFactory<IpFilterGatewayFilterFactory.Config> {

    private final PolicyRegistry policyRegistry;
    private final ObjectMapper objectMapper;

    @Data
    public static class Config {
        private String policyName;
    }

    public IpFilterGatewayFilterFactory(PolicyRegistry policyRegistry, ObjectMapper objectMapper) {
        super(Config.class);
        this.policyRegistry = policyRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("policyName");
    }

    @Override
    public GatewayFilter apply(Config config) {
        var policy = policyRegistry.find(config.getPolicyName())
                .orElseThrow(() -> new IllegalStateException("Policy not found: " + config.getPolicyName()));

        try {
            var ipConfig = objectMapper.convertValue(policy.getSpec().getConfig(), SecurityPolicyConfig.IpFilter.class);
            return new IpFilterGatewayFilter(ipConfig.getAllowList());
        } catch (Exception e) {
            log.error("Failed to parse IpFilter config for '{}': {}", config.getPolicyName(), e.getMessage());
            return new IpFilterGatewayFilter(List.of());
        }
    }
}

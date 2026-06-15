package com.example.gw.standalone;

import com.example.gw.model.*;
import com.example.gw.policy.PolicyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class StandaloneConfigLoader {

    private final String configDir;
    private final ObjectMapper objectMapper;
    private final PolicyRegistry policyRegistry;
    private volatile LoadedConfig cached;

    public StandaloneConfigLoader(
            @Value("${gateway.standalone.config-dir}") String configDir,
            ObjectMapper objectMapper,
            PolicyRegistry policyRegistry) {
        this.configDir = configDir;
        this.objectMapper = objectMapper;
        this.policyRegistry = policyRegistry;
    }

    public LoadedConfig getConfig() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = doLoad();
                }
            }
        }
        return cached;
    }

    private LoadedConfig doLoad() {
        File dir = new File(configDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Config directory not found: {}", dir.getAbsolutePath());
            return LoadedConfig.empty();
        }

        File[] files = dir.listFiles(f -> f.getName().endsWith(".json"));
        if (files == null) files = new File[0];
        log.info("Scanning {} JSON files in {}", files.length, dir.getAbsolutePath());

        List<ListenerResource> listeners = new ArrayList<>();
        GatewayResource[] gateway = {null};
        List<RouterResource> routers = new ArrayList<>();
        Map<String, ConnectorResource> connectors = new HashMap<>();
        Map<String, FlowResource> flows = new HashMap<>();
        List<PolicyResource> policies = new ArrayList<>();

        for (File file : files) {
            try {
                var tree = objectMapper.readTree(file);
                String kind = tree.path("kind").asText(null);
                if (kind == null) {
                    log.warn("Missing 'kind' field in {}", file.getName());
                    continue;
                }
                switch (kind) {
                    case "Listener"  -> listeners.add(objectMapper.readValue(file, ListenerResource.class));
                    case "Gateway"   -> gateway[0] = objectMapper.readValue(file, GatewayResource.class);
                    case "Router"    -> routers.add(objectMapper.readValue(file, RouterResource.class));
                    case "Connector" -> { var c = objectMapper.readValue(file, ConnectorResource.class); connectors.put(c.getMetadata().getName(), c); }
                    case "Flow"      -> { var f = objectMapper.readValue(file, FlowResource.class); flows.put(f.getMetadata().getName(), f); }
                    case "Policy"    -> {
                        String type = tree.path("type").asText(null);
                        if (type == null || type.isBlank()) {
                            log.warn("Policy '{}' missing 'type' field — skipping", file.getName());
                            continue;
                        }
                        var p = objectMapper.readValue(file, PolicyResource.class);
                        if (p.getSpec().getTargetRef() == null) {
                            log.warn("Policy '{}' missing spec.targetRef — skipping", p.getMetadata().getName());
                            continue;
                        }
                        policies.add(p);
                        policyRegistry.register(p);
                    }
                    default          -> log.warn("Unknown kind '{}' in {}", kind, file.getName());
                }
                log.debug("Loaded {} from {}", kind, file.getName());
            } catch (Exception e) {
                log.error("Failed to parse {}: {}", file.getName(), e.getMessage());
            }
        }

        return LoadedConfig.builder()
                .listeners(listeners)
                .gateway(gateway[0])
                .routers(routers)
                .connectors(connectors)
                .flows(flows)
                .policies(policies)
                .build();
    }
}

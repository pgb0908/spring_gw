package com.example.gw.standalone;

import com.example.gw.model.ConnectorResource;
import com.example.gw.model.FlowResource;
import com.example.gw.model.RouterResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DestinationResolver {

    public ResolvedDestination resolve(RouterResource.DestinationRef ref, LoadedConfig config) {
        return switch (ref.getKind()) {
            case Connector -> resolveConnector(ref.getName(), config);
            case Flow      -> resolveFlow(ref.getName(), config);
        };
    }

    private ResolvedDestination resolveConnector(String name, LoadedConfig config) {
        ConnectorResource connector = config.getConnectors().get(name);
        if (connector == null) return null;

        var target = firstTarget(connector.getSpec().getLoadBalancing().getTargets(), "Connector", name);
        if (target == null) return null;

        String scheme = "HTTPS".equals(connector.getSpec().getProtocol()) ? "https" : "http";
        return new ResolvedDestination(scheme + "://" + target.getHost() + ":" + target.getPort(), List.of(), Map.of());
    }

    private ResolvedDestination resolveFlow(String name, LoadedConfig config) {
        FlowResource flow = config.getFlows().get(name);
        if (flow == null) return null;

        var target = firstTarget(flow.getSpec().getLoadBalancing().getTargets(), "Flow", name);
        if (target == null) return null;

        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("destinationKind", RouterResource.DestinationKind.Flow.name());
        if (target.getFlowId() != null && !target.getFlowId().isBlank()) {
            metadata.put("flowId", target.getFlowId());
        }
        return new ResolvedDestination("grpc://" + target.getHost() + ":" + target.getPort(), List.of(), metadata);
    }

    private static <T> T firstTarget(List<T> targets, String kind, String name) {
        if (targets.isEmpty()) {
            log.warn("{} '{}' has no targets", kind, name);
            return null;
        }
        if (targets.size() > 1) {
            log.warn("{} '{}' has {} targets — only first will be used", kind, name, targets.size());
        }
        return targets.get(0);
    }

    public record ResolvedDestination(String uri, List<FilterDefinition> filters, Map<String, Object> metadata) {}
}

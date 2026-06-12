package com.example.gw.standalone;

import com.example.gw.model.ConnectorResource;
import com.example.gw.model.FlowResource;
import com.example.gw.model.PolicyResource;
import com.example.gw.model.RouterResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RouteTranslator {

    public List<RouteDefinition> translate(LoadedConfig config) {
        return config.getRouters().stream()
                .map(router -> toRouteDefinition(router, config))
                .filter(r -> r != null)
                .toList();
    }

    private RouteDefinition toRouteDefinition(RouterResource router, LoadedConfig config) {
        var destinations = router.getSpec().getDestinations();
        if (destinations.isEmpty()) {
            log.warn("Router '{}' has no destinations — skipping", router.getMetadata().getName());
            return null;
        }
        if (destinations.size() > 1) {
            log.warn("Router '{}' has {} destinations with weights — weighted routing not implemented, using first only",
                    router.getMetadata().getName(), destinations.size());
        }

        var ref = destinations.get(0).getDestinationRef();
        var resolved = switch (ref.getKind()) {
            case Connector -> resolveConnector(ref.getName(), config);
            case Flow      -> resolveFlow(ref.getName(), config);
        };

        if (resolved == null) {
            log.warn("Router '{}' references unknown {} '{}' — skipping",
                    router.getMetadata().getName(), ref.getKind(), ref.getName());
            return null;
        }

        var route = new RouteDefinition();
        route.setId(router.getMetadata().getName());
        route.setUri(URI.create(resolved.uri()));
        route.setPredicates(List.of(
                new PredicateDefinition("Path=" + router.getSpec().getRule().getMatch().getPath()),
                new PredicateDefinition("Method=" + router.getSpec().getRule().getMatch().getMethods())
        ));

        List<FilterDefinition> filters = new ArrayList<>(policyFilters(router.getMetadata().getName(), config));
        filters.addAll(resolved.filters());
        route.setFilters(filters);
        route.setMetadata(new HashMap<>(resolved.metadata()));
        return route;
    }

    private ResolvedDestination resolveConnector(String name, LoadedConfig config) {
        ConnectorResource connector = config.getConnectors().get(name);
        if (connector == null) return null;

        var targets = connector.getSpec().getLoadBalancing().getTargets();
        if (targets.isEmpty()) {
            log.warn("Connector '{}' has no targets", name);
            return null;
        }
        var target = targets.get(0);
        String scheme = "HTTPS".equals(connector.getSpec().getProtocol()) ? "https" : "http";
        return new ResolvedDestination(scheme + "://" + target.getHost() + ":" + target.getPort(), List.of(), Map.of());
    }

    private ResolvedDestination resolveFlow(String name, LoadedConfig config) {
        FlowResource flow = config.getFlows().get(name);
        if (flow == null) return null;

        var targets = flow.getSpec().getLoadBalancing().getTargets();
        if (targets.isEmpty()) {
            log.warn("Flow '{}' has no targets", name);
            return null;
        }
        var target = targets.get(0);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("destinationKind", "Flow");
        if (target.getFlowId() != null && !target.getFlowId().isBlank()) {
            metadata.put("flowId", target.getFlowId());
        }
        return new ResolvedDestination("grpc://" + target.getHost() + ":" + target.getPort(), List.of(), metadata);
    }

    private List<FilterDefinition> policyFilters(String routerName, LoadedConfig config) {
        return config.getPolicies().stream()
                .filter(p -> routerName.equals(p.getSpec().getTargetRef().getName()))
                .sorted(Comparator.comparingInt(p -> p.getSpec().getOrder()))
                .map(this::toPolicyFilterDefinition)
                .filter(f -> f != null)
                .toList();
    }

    private FilterDefinition toPolicyFilterDefinition(PolicyResource policy) {
        return new FilterDefinition(policy.getType() + "=" + policy.getMetadata().getName());
    }

    private record ResolvedDestination(String uri, List<FilterDefinition> filters, Map<String, Object> metadata) {}
}

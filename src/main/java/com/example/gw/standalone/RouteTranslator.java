package com.example.gw.standalone;

import com.example.gw.model.ConnectorResource;
import com.example.gw.model.FlowResource;
import com.example.gw.model.RouterResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

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
        route.setFilters(resolved.filters());
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
        return new ResolvedDestination(scheme + "://" + target.getHost() + ":" + target.getPort(), List.of());
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
        var tls = flow.getSpec().getUpstreamTls();
        String scheme = (tls != null && tls.isEnabled()) ? "https" : "h2c";
        List<FilterDefinition> filters = target.getFlowId() != null && !target.getFlowId().isBlank()
                ? List.of(new FilterDefinition("AddRequestHeader=X-Flow-Id, " + target.getFlowId()))
                : List.of();
        return new ResolvedDestination(scheme + "://" + target.getHost() + ":" + target.getPort(), filters);
    }

    private record ResolvedDestination(String uri, List<FilterDefinition> filters) {}
}

package com.example.gw.standalone;

import com.example.gw.filter.PolicyRegistry;
import com.example.gw.model.RouterResource;
import lombok.RequiredArgsConstructor;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class RouteTranslator {

    private final PolicyRegistry policyRegistry;
    private final DestinationResolver destinationResolver;

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
            log.warn("Router '{}' has {} destinations — weighted routing not implemented, using first only",
                    router.getMetadata().getName(), destinations.size());
        }

        var ref = destinations.get(0).getDestinationRef();
        var resolved = destinationResolver.resolve(ref, config);
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

        List<FilterDefinition> filters = new ArrayList<>(policyFilters(router.getMetadata().getName()));
        filters.addAll(resolved.filters());
        route.setFilters(filters);
        route.setMetadata(new HashMap<>(resolved.metadata()));
        return route;
    }

    private List<FilterDefinition> policyFilters(String routerName) {
        return policyRegistry.findByRouter(routerName).stream()
                .sorted(Comparator.comparingInt(p -> p.getSpec().getOrder()))
                .map(p -> new FilterDefinition(p.getType() + "=" + p.getMetadata().getName()))
                .toList();
    }
}

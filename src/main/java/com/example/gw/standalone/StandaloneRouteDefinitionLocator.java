package com.example.gw.standalone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class StandaloneRouteDefinitionLocator implements RouteDefinitionLocator {

    private final StandaloneConfigLoader loader;
    private final RouteTranslator translator;

    public StandaloneRouteDefinitionLocator(StandaloneConfigLoader loader, RouteTranslator translator) {
        this.loader = loader;
        this.translator = translator;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(translator.translate(loader.getConfig()));
    }
}

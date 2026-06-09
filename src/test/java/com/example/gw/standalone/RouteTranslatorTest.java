package com.example.gw.standalone;

import com.example.gw.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTranslatorTest {

    private final RouteTranslator translator = new RouteTranslator();

    // ── 동작 5: Connector 목적지 → http://host:port URI ───────────────────
    @Test
    void Connector_목적지는_http_URI로_변환된다() {
        var config = configWith(
                router("my-router", "/api/**", "GET", RouterResource.DestinationKind.Connector, "svc"),
                connector("svc", "HTTP", "10.0.0.1", 8080)
        );

        var routes = translator.translate(config);

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getId()).isEqualTo("my-router");
        assertThat(routes.get(0).getUri().toString()).isEqualTo("http://10.0.0.1:8080");
        assertThat(routes.get(0).getFilters()).isEmpty();
    }

    // ── 동작 6: HTTPS Connector → https URI ──────────────────────────────
    @Test
    void HTTPS_Connector는_https_URI로_변환된다() {
        var config = configWith(
                router("r1", "/secure/**", "GET", RouterResource.DestinationKind.Connector, "secure-svc"),
                connector("secure-svc", "HTTPS", "10.0.0.2", 8443)
        );

        var routes = translator.translate(config);

        assertThat(routes.get(0).getUri().getScheme()).isEqualTo("https");
    }

    // ── 동작 7: Flow 목적지 → h2c URI + X-Flow-Id 헤더 ───────────────────
    @Test
    void Flow_목적지는_h2c_URI와_X_Flow_Id_헤더로_변환된다() {
        var config = configWithFlow(
                router("r1", "/flow/**", "POST", RouterResource.DestinationKind.Flow, "my-flow"),
                flow("my-flow", "10.0.0.3", 9090, "flow-xyz")
        );

        var routes = translator.translate(config);

        assertThat(routes.get(0).getUri().getScheme()).isEqualTo("h2c");
        assertThat(routes.get(0).getUri().toString()).isEqualTo("h2c://10.0.0.3:9090");
        assertThat(routes.get(0).getFilters()).hasSize(1);
        assertThat(routes.get(0).getFilters().get(0).getName()).isEqualTo("AddRequestHeader");
        assertThat(routes.get(0).getFilters().get(0).getArgs()).containsValue("flow-xyz");
    }

    // ── 동작 8: 목적지 없는 Router는 건너뛴다 ────────────────────────────
    @Test
    void 목적지_없는_Router는_결과에_포함되지_않는다() {
        var router = new RouterResource();
        router.getMetadata().setName("empty-router");

        var config = LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of()).flows(Map.of())
                .build();

        assertThat(translator.translate(config)).isEmpty();
    }

    // ── 동작 9: 존재하지 않는 Connector 참조는 건너뛴다 ──────────────────
    @Test
    void 존재하지_않는_Connector_참조_Router는_건너뛴다() {
        var config = configWith(
                router("r1", "/api/**", "GET", RouterResource.DestinationKind.Connector, "nonexistent"),
                connector("other-svc", "HTTP", "localhost", 8080)
        );

        assertThat(translator.translate(config)).isEmpty();
    }

    // ── Path/Method predicate 검증 ────────────────────────────────────────
    @Test
    void Path와_Method_predicate가_올바르게_설정된다() {
        var config = configWith(
                router("r1", "/users/**", "POST", RouterResource.DestinationKind.Connector, "svc"),
                connector("svc", "HTTP", "localhost", 8080)
        );

        var route = translator.translate(config).get(0);
        var predicateNames = route.getPredicates().stream().map(p -> p.getName()).toList();
        assertThat(predicateNames).contains("Path", "Method");
    }

    // ── 헬퍼 메서드 ────────────────────────────────────────────────────────

    private LoadedConfig configWith(RouterResource router, ConnectorResource connector) {
        return LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of(connector.getMetadata().getName(), connector))
                .flows(Map.of())
                .build();
    }

    private LoadedConfig configWithFlow(RouterResource router, FlowResource flow) {
        return LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of())
                .flows(Map.of(flow.getMetadata().getName(), flow))
                .build();
    }

    private RouterResource router(String name, String path, String method, RouterResource.DestinationKind kind, String destName) {
        var router = new RouterResource();
        router.getMetadata().setName(name);
        router.getSpec().getRule().getMatch().setPath(path);
        router.getSpec().getRule().getMatch().setMethods(method);
        var dest = new RouterResource.Destination();
        dest.getDestinationRef().setKind(kind);
        dest.getDestinationRef().setName(destName);
        router.getSpec().setDestinations(List.of(dest));
        return router;
    }

    private ConnectorResource connector(String name, String protocol, String host, int port) {
        var c = new ConnectorResource();
        c.getMetadata().setName(name);
        c.getSpec().setProtocol(protocol);
        var target = new ConnectorResource.Target();
        target.setHost(host);
        target.setPort(port);
        c.getSpec().getLoadBalancing().setTargets(List.of(target));
        return c;
    }

    private FlowResource flow(String name, String host, int port, String flowId) {
        var f = new FlowResource();
        f.getMetadata().setName(name);
        var target = new FlowResource.Target();
        target.setHost(host);
        target.setPort(port);
        target.setFlowId(flowId);
        f.getSpec().getLoadBalancing().setTargets(List.of(target));
        return f;
    }
}

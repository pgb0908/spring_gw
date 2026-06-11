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

    // ── 동작 7: Flow 목적지 → grpc:// URI + metadata(destinationKind, flowId) ─
    @Test
    void Flow_목적지는_grpc_URI와_metadata로_변환된다() {
        var config = configWithFlow(
                router("r1", "/flow/**", "POST", RouterResource.DestinationKind.Flow, "my-flow"),
                flow("my-flow", "10.0.0.3", 9090, "flow-xyz")
        );

        var routes = translator.translate(config);

        assertThat(routes.get(0).getUri().getScheme()).isEqualTo("grpc");
        assertThat(routes.get(0).getUri().toString()).isEqualTo("grpc://10.0.0.3:9090");
        assertThat(routes.get(0).getMetadata()).containsEntry("destinationKind", "Flow");
        assertThat(routes.get(0).getMetadata()).containsEntry("flowId", "flow-xyz");
        assertThat(routes.get(0).getFilters())
                .noneMatch(f -> f.getName().equals("FlowGateway"));
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

    // ── 동작 10: Policy가 없는 Router는 Policy 필터 없이 변환된다 ─────────
    @Test
    void Policy가_없는_Router는_기존_필터만_유지된다() {
        var config = configWith(
                router("r1", "/api/**", "GET", RouterResource.DestinationKind.Connector, "svc"),
                connector("svc", "HTTP", "localhost", 8080)
        );

        var route = translator.translate(config).get(0);

        assertThat(route.getFilters()).isEmpty();
    }

    // ── 동작 11: Policy 1개가 order 위치에 필터로 추가된다 ───────────────
    @Test
    void Router에_Policy_1개가_있으면_해당_필터가_추가된다() {
        var router = router("r1", "/api/**", "GET", RouterResource.DestinationKind.Connector, "svc");
        var conn = connector("svc", "HTTP", "localhost", 8080);
        var policy = policy("p1", "Security", "r1", 5);

        var config = LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of("svc", conn))
                .flows(Map.of())
                .policies(List.of(policy))
                .build();

        var route = translator.translate(config).get(0);

        assertThat(route.getFilters()).hasSize(1);
        assertThat(route.getFilters().get(0).getName()).isEqualTo("SecurityPolicy");
        assertThat(route.getFilters().get(0).getArgs()).containsValue("p1");
    }

    // ── 동작 12: 여러 Policy가 order 오름차순으로 정렬된다 ──────────────
    @Test
    void 여러_Policy가_order_오름차순으로_필터에_추가된다() {
        var router = router("r1", "/api/**", "GET", RouterResource.DestinationKind.Connector, "svc");
        var conn = connector("svc", "HTTP", "localhost", 8080);
        var traffic = policy("traffic-p", "Traffic", "r1", 10);
        var security = policy("security-p", "Security", "r1", 5);

        var config = LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of("svc", conn))
                .flows(Map.of())
                .policies(List.of(traffic, security))
                .build();

        var route = translator.translate(config).get(0);

        assertThat(route.getFilters()).hasSize(2);
        assertThat(route.getFilters().get(0).getName()).isEqualTo("SecurityPolicy");
        assertThat(route.getFilters().get(1).getName()).isEqualTo("TrafficPolicy");
    }

    // ── 동작 13: 미구현 Policy type은 스킵된다 ──────────────────────────
    @Test
    void 미구현_Policy_type은_필터에_추가되지_않는다() {
        var router = router("r1", "/api/**", "GET", RouterResource.DestinationKind.Connector, "svc");
        var conn = connector("svc", "HTTP", "localhost", 8080);
        var unknown = policy("p1", "UnknownType", "r1", 5);

        var config = LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of("svc", conn))
                .flows(Map.of())
                .policies(List.of(unknown))
                .build();

        var route = translator.translate(config).get(0);

        assertThat(route.getFilters()).isEmpty();
    }

    // ── 헬퍼 메서드 ────────────────────────────────────────────────────────

    private LoadedConfig configWith(RouterResource router, ConnectorResource connector) {
        return LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of(connector.getMetadata().getName(), connector))
                .flows(Map.of())
                .policies(List.of())
                .build();
    }

    private LoadedConfig configWithFlow(RouterResource router, FlowResource flow) {
        return LoadedConfig.builder()
                .listeners(List.of()).gateway(null)
                .routers(List.of(router))
                .connectors(Map.of())
                .flows(Map.of(flow.getMetadata().getName(), flow))
                .policies(List.of())
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

    private com.example.gw.model.PolicyResource policy(String name, String type, String routerName, int order) {
        var p = new com.example.gw.model.PolicyResource();
        p.getMetadata().setName(name);
        p.setType(type);
        var targetRef = new com.example.gw.model.PolicyResource.TargetRef();
        targetRef.setName(routerName);
        p.getSpec().setTargetRef(targetRef);
        p.getSpec().setOrder(order);
        return p;
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

package com.example.gw.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneConfigLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StandaloneConfigLoader loader(String dir) {
        return new StandaloneConfigLoader(dir, objectMapper);
    }

    // ── 동작 1: 디렉토리의 모든 리소스 타입을 로드한다 ──────────────────────
    @Test
    void 모든_리소스_타입을_올바르게_분류해_로드한다(@TempDir Path dir) throws Exception {
        write(dir, "listener.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Listener","metadata":{"name":"l1"},"spec":{"protocol":"HTTP","port":8080}}""");
        write(dir, "gateway.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Gateway","metadata":{"name":"gw1"},"spec":{"logging":{"accessLog":{"enabled":true,"format":"JSON"}}}}""");
        write(dir, "router.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Router","metadata":{"name":"r1"},"spec":{"rule":{"protocol":"HTTP","match":{"path":"/api/**","methods":"GET"}},"destinations":[{"destinationRef":{"kind":"Connector","name":"c1"}}]}}""");
        write(dir, "connector.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","metadata":{"name":"c1"},"spec":{"loadBalancing":{"targets":[{"host":"localhost","port":8081}]}}}""");
        write(dir, "flow.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Flow","metadata":{"name":"f1"},"spec":{"loadBalancing":{"targets":[{"host":"localhost","port":9090,"flow-id":"abc"}]}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getListeners()).hasSize(1);
        assertThat(config.getListeners().get(0).getMetadata().getName()).isEqualTo("l1");
        assertThat(config.getGateway()).isNotNull();
        assertThat(config.getGateway().getMetadata().getName()).isEqualTo("gw1");
        assertThat(config.getRouters()).hasSize(1);
        assertThat(config.getConnectors()).containsKey("c1");
        assertThat(config.getFlows()).containsKey("f1");
    }

    // ── 동작 2: 존재하지 않는 디렉토리는 빈 LoadedConfig를 반환한다 ──────────
    @Test
    void 존재하지_않는_디렉토리면_빈_설정을_반환한다() {
        var config = loader("/nonexistent/path").getConfig();

        assertThat(config.getListeners()).isEmpty();
        assertThat(config.getGateway()).isNull();
        assertThat(config.getRouters()).isEmpty();
        assertThat(config.getConnectors()).isEmpty();
        assertThat(config.getFlows()).isEmpty();
    }

    // ── 동작 3: 알 수 없는 kind 파일은 조용히 건너뛴다 ───────────────────────
    @Test
    void 알수없는_kind는_건너뛰고_나머지를_로드한다(@TempDir Path dir) throws Exception {
        write(dir, "unknown.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Unknown","metadata":{"name":"x"},"spec":{}}""");
        write(dir, "connector.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","metadata":{"name":"c1"},"spec":{"loadBalancing":{"targets":[{"host":"localhost","port":8081}]}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getConnectors()).containsKey("c1");
        assertThat(config.getRouters()).isEmpty();
    }

    // ── 동작 4: 깨진 JSON은 앱을 죽이지 않고 나머지를 계속 로드한다 ──────────
    @Test
    void 깨진_JSON_파일이_있어도_나머지_파일을_로드한다(@TempDir Path dir) throws Exception {
        writeRaw(dir, "broken.json", "{ this is not valid json }}}");
        write(dir, "connector.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","metadata":{"name":"c1"},"spec":{"loadBalancing":{"targets":[{"host":"localhost","port":8081}]}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getConnectors()).containsKey("c1");
    }

    // ── 동작 5-1: kind=Policy 파일이 LoadedConfig.policies에 포함된다 ────────
    @Test
    void Policy_파일을_로드해_policies_목록에_포함한다(@TempDir Path dir) throws Exception {
        write(dir, "policy.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Policy","type":"Security","metadata":{"name":"orders-security"},
                 "spec":{"targetRef":{"kind":"Router","name":"route-to-orders"},"order":5,"config":{"ipFilter":{"allowList":["10.0.0.0/8"]}}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getPolicies()).hasSize(1);
        assertThat(config.getPolicies().get(0).getMetadata().getName()).isEqualTo("orders-security");
        assertThat(config.getPolicies().get(0).getType()).isEqualTo("Security");
        assertThat(config.getPolicies().get(0).getSpec().getTargetRef().getName()).isEqualTo("route-to-orders");
        assertThat(config.getPolicies().get(0).getSpec().getOrder()).isEqualTo(5);
    }

    // ── 동작 5-2: type 필드 누락 Policy는 스킵된다 ───────────────────────────
    @Test
    void type_필드_없는_Policy는_스킵된다(@TempDir Path dir) throws Exception {
        write(dir, "bad-policy.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Policy","metadata":{"name":"bad"},
                 "spec":{"targetRef":{"kind":"Router","name":"r1"},"order":5,"config":{}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getPolicies()).isEmpty();
    }

    // ── 동작 5-3: targetRef 없는 Policy는 스킵된다 ───────────────────────────
    @Test
    void targetRef_없는_Policy는_스킵된다(@TempDir Path dir) throws Exception {
        write(dir, "no-ref-policy.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Policy","type":"Security","metadata":{"name":"p1"},
                 "spec":{"order":5,"config":{}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getPolicies()).isEmpty();
    }

    // ── 동작 5: Connector와 Flow를 이름으로 인덱싱한다 ───────────────────────
    @Test
    void Connector와_Flow를_metadata_name으로_인덱싱한다(@TempDir Path dir) throws Exception {
        write(dir, "c1.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","metadata":{"name":"backend-a"},"spec":{"loadBalancing":{"targets":[{"host":"host-a","port":8080}]}}}""");
        write(dir, "c2.json", """
                {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","metadata":{"name":"backend-b"},"spec":{"loadBalancing":{"targets":[{"host":"host-b","port":8080}]}}}""");

        var config = loader(dir.toString()).getConfig();

        assertThat(config.getConnectors()).containsKeys("backend-a", "backend-b");
        assertThat(config.getConnectors().get("backend-a").getSpec().getLoadBalancing().getTargets().get(0).getHost()).isEqualTo("host-a");
    }

    private void write(Path dir, String name, String json) throws Exception {
        new ObjectMapper().writeValue(new File(dir.toFile(), name), new ObjectMapper().readTree(json));
    }

    private void writeRaw(Path dir, String name, String content) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(name), content);
    }
}

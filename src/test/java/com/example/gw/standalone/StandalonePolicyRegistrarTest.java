package com.example.gw.standalone;

import com.example.gw.model.PolicyResource;
import com.example.gw.model.ResourceMetadata;
import com.example.gw.policy.PolicyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class StandalonePolicyRegistrarTest {

    @Test
    void afterPropertiesSet_호출시_LoadedConfig의_모든_Policy를_registry에_등록한다() {
        PolicyResource p1 = makePolicy("jwt-policy");
        PolicyResource p2 = makePolicy("ip-policy");

        StandaloneConfigLoader configLoader = mock(StandaloneConfigLoader.class);
        when(configLoader.getConfig()).thenReturn(
                LoadedConfig.builder().policies(List.of(p1, p2)).build());

        PolicyRegistry registry = mock(PolicyRegistry.class);
        StandalonePolicyRegistrar registrar = new StandalonePolicyRegistrar(configLoader, registry);

        registrar.afterPropertiesSet();

        verify(registry).register(p1);
        verify(registry).register(p2);
        verifyNoMoreInteractions(registry);
    }

    @Test
    void Policy가_없을_때_register가_호출되지_않는다() {
        StandaloneConfigLoader configLoader = mock(StandaloneConfigLoader.class);
        when(configLoader.getConfig()).thenReturn(LoadedConfig.builder().build());

        PolicyRegistry registry = mock(PolicyRegistry.class);
        StandalonePolicyRegistrar registrar = new StandalonePolicyRegistrar(configLoader, registry);

        registrar.afterPropertiesSet();

        verifyNoInteractions(registry);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private PolicyResource makePolicy(String name) {
        PolicyResource p = new PolicyResource();
        ResourceMetadata meta = new ResourceMetadata();
        meta.setName(name);
        p.setMetadata(meta);
        PolicyResource.Spec spec = new PolicyResource.Spec();
        PolicyResource.TargetRef ref = new PolicyResource.TargetRef();
        ref.setKind("Router");
        ref.setName("my-router");
        spec.setTargetRef(ref);
        p.setSpec(spec);
        return p;
    }
}

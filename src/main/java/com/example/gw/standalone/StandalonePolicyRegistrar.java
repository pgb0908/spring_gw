package com.example.gw.standalone;

import com.example.gw.policy.PolicyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class StandalonePolicyRegistrar implements InitializingBean {

    private final StandaloneConfigLoader configLoader;
    private final PolicyRegistry policyRegistry;

    @Override
    public void afterPropertiesSet() {
        var policies = configLoader.getConfig().getPolicies();
        if (policies == null) return;
        policies.forEach(p -> {
            policyRegistry.register(p);
            log.debug("Policy 등록 — name={}", p.getMetadata().getName());
        });
        log.info("Policy 등록 완료 — {}개", policies.size());
    }
}

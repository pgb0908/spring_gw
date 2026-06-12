package com.example.gw.policy;

import com.example.gw.model.PolicyResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryPolicyRegistry implements PolicyRegistry {

    private final Map<String, PolicyResource> byName = new ConcurrentHashMap<>();
    private final Map<String, List<PolicyResource>> byRouter = new ConcurrentHashMap<>();

    @Override
    public void register(PolicyResource policy) {
        byName.put(policy.getMetadata().getName(), policy);
        String routerName = policy.getSpec().getTargetRef().getName();
        byRouter.computeIfAbsent(routerName, k -> new ArrayList<>()).add(policy);
    }

    @Override
    public Optional<PolicyResource> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<PolicyResource> findByRouter(String routerName) {
        return byRouter.getOrDefault(routerName, List.of());
    }
}

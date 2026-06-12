package com.example.gw.policy;

import com.example.gw.model.PolicyResource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryPolicyRegistry implements PolicyRegistry {

    private final Map<String, PolicyResource> store = new ConcurrentHashMap<>();

    @Override
    public void register(PolicyResource policy) {
        store.put(policy.getMetadata().getName(), policy);
    }

    @Override
    public Optional<PolicyResource> find(String name) {
        return Optional.ofNullable(store.get(name));
    }
}

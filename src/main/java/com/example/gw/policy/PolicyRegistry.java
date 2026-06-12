package com.example.gw.policy;

import com.example.gw.model.PolicyResource;

import java.util.Optional;

public interface PolicyRegistry {
    void register(PolicyResource policy);
    Optional<PolicyResource> find(String name);
}

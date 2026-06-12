package com.example.gw.filter;

import com.example.gw.model.PolicyResource;

import java.util.List;
import java.util.Optional;

public interface PolicyRegistry {
    void register(PolicyResource policy);
    Optional<PolicyResource> find(String name);
    List<PolicyResource> findByRouter(String routerName);
}

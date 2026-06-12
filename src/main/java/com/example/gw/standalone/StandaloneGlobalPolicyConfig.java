package com.example.gw.standalone;

import com.example.gw.model.GatewayResource;
import com.example.gw.standalone.StandaloneConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
public class StandaloneGlobalPolicyConfig {

    private final StandaloneConfigLoader loader;

    public StandaloneGlobalPolicyConfig(StandaloneConfigLoader loader) {
        this.loader = loader;
    }

    @Bean
    public CorsWebFilter standaloneCorsWebFilter() {
        var policy = loader.getConfig().getGateway();
        var corsSpec = policy != null && policy.getSpec().getGlobalPolicy() != null
                ? policy.getSpec().getGlobalPolicy().getCors()
                : null;

        CorsConfiguration config;
        if (corsSpec != null) {
            log.info("Applying CORS from Gateway config");
            config = new CorsConfiguration();
            config.setAllowedOriginPatterns(corsSpec.getAllowOrigins().isEmpty() ? List.of("*") : corsSpec.getAllowOrigins());
            config.setAllowedMethods(corsSpec.getAllowMethods().isEmpty() ? List.of("*") : corsSpec.getAllowMethods());
            config.setAllowedHeaders(corsSpec.getAllowHeaders().isEmpty() ? List.of("*") : corsSpec.getAllowHeaders());
            config.setExposedHeaders(corsSpec.getExposeHeaders());
            if (corsSpec.isAllowCredentials()) config.setAllowCredentials(true);
            config.setMaxAge((long) corsSpec.getMaxAge());
        } else {
            log.debug("No Gateway CORS config — using permissive defaults");
            config = new CorsConfiguration();
            config.setAllowedOriginPatterns(List.of("*"));
            config.setAllowedMethods(List.of("*"));
            config.setAllowedHeaders(List.of("*"));
        }

        var globalPolicy = policy != null ? policy.getSpec().getGlobalPolicy() : null;
        if (globalPolicy != null) {
            if (globalPolicy.getIpFilter() != null) log.warn("globalPolicy.ipFilter is configured but not yet implemented");
            if (globalPolicy.getRateLimit() != null) log.warn("globalPolicy.rateLimit is configured but not yet implemented");
        }

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}

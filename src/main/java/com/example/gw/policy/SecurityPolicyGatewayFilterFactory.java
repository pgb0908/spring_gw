package com.example.gw.policy;

import com.example.gw.model.PolicyResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SecurityPolicyGatewayFilterFactory extends AbstractGatewayFilterFactory<SecurityPolicyGatewayFilterFactory.Config> {

    private final ObjectMapper objectMapper;

    @Data
    public static class Config {
        private PolicyResource policyResource;
    }

    public SecurityPolicyGatewayFilterFactory(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        SecurityPolicyConfig secConfig = parseConfig(config.getPolicyResource());
        return (exchange, chain) -> {
            // IP filter
            if (secConfig.getIpFilter() != null && !secConfig.getIpFilter().getAllowList().isEmpty()) {
                InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
                if (remoteAddress != null) {
                    String clientIp = remoteAddress.getAddress().getHostAddress();
                    if (!isAllowed(clientIp, secConfig.getIpFilter().getAllowList())) {
                        log.warn("IP {} is not in allowList — blocked", clientIp);
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                }
            }

            // JWT validation
            if (secConfig.getJwtValidation() != null) {
                String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
                String token = authHeader.substring(7);
                try {
                    Map<String, String> claimHeaders = validateJwt(token, secConfig.getJwtValidation());
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .headers(h -> claimHeaders.forEach(h::set))
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                } catch (Exception e) {
                    log.warn("JWT validation failed: {}", e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }

            return chain.filter(exchange);
        };
    }

    private SecurityPolicyConfig parseConfig(PolicyResource policy) {
        try {
            return objectMapper.convertValue(policy.getSpec().getConfig(), SecurityPolicyConfig.class);
        } catch (Exception e) {
            log.error("Failed to parse SecurityPolicy config for '{}': {}", policy.getMetadata().getName(), e.getMessage());
            return new SecurityPolicyConfig();
        }
    }

    private boolean isAllowed(String clientIp, List<String> allowList) {
        for (String cidr : allowList) {
            try {
                if (matchesCidr(clientIp, cidr)) return true;
            } catch (Exception e) {
                log.warn("Invalid CIDR '{}': {}", cidr, e.getMessage());
            }
        }
        return false;
    }

    private boolean matchesCidr(String clientIp, String cidr) throws Exception {
        if (!cidr.contains("/")) {
            return clientIp.equals(cidr);
        }
        String[] parts = cidr.split("/");
        byte[] network = InetAddress.getByName(parts[0]).getAddress();
        byte[] client = InetAddress.getByName(clientIp).getAddress();
        if (network.length != client.length) return false;
        int prefix = Integer.parseInt(parts[1]);
        int fullBytes = prefix / 8;
        int remainder = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != client[i]) return false;
        }
        if (remainder > 0 && fullBytes < network.length) {
            int mask = 0xFF << (8 - remainder);
            if ((network[fullBytes] & mask) != (client[fullBytes] & mask)) return false;
        }
        return true;
    }

    private Map<String, String> validateJwt(String token, SecurityPolicyConfig.JwtValidation jwtConfig) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(token);

        RSAKey rsaKey = RSAKey.parse(jwtConfig.getPublicKey().toString());
        JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
        if (!signedJWT.verify(verifier)) {
            throw new IllegalArgumentException("JWT signature verification failed");
        }

        var claims = signedJWT.getJWTClaimsSet();
        Date expiration = claims.getExpirationTime();
        if (expiration != null && expiration.before(new Date())) {
            throw new IllegalArgumentException("JWT token has expired");
        }

        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : jwtConfig.getClaimsToHeaders().entrySet()) {
            Object claimValue = claims.getClaim(entry.getKey());
            if (claimValue != null) {
                headers.put(entry.getValue(), claimValue.toString());
            }
        }
        return headers;
    }
}

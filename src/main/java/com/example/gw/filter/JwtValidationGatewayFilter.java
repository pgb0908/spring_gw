package com.example.gw.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class JwtValidationGatewayFilter implements GatewayFilter {

    private final JsonNode publicKey;
    private final Map<String, String> claimsToHeaders;

    public JwtValidationGatewayFilter(JsonNode publicKey, Map<String, String> claimsToHeaders) {
        this.publicKey = publicKey;
        this.claimsToHeaders = claimsToHeaders;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        try {
            Map<String, String> claimHeaders = validateAndExtract(token);
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

    private Map<String, String> validateAndExtract(String token) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(token);

        RSAKey rsaKey = RSAKey.parse(publicKey.toString());
        JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
        if (!signedJWT.verify(verifier)) {
            throw new IllegalArgumentException("JWT signature verification failed");
        }

        var claims = signedJWT.getJWTClaimsSet();
        Date expiration = claims.getExpirationTime();
        if (expiration != null && expiration.before(new Date())) {
            throw new IllegalArgumentException("JWT token has expired");
        }

        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : claimsToHeaders.entrySet()) {
            Object claimValue = claims.getClaim(entry.getKey());
            if (claimValue != null) {
                headers.put(entry.getValue(), claimValue.toString());
            }
        }
        return headers;
    }
}

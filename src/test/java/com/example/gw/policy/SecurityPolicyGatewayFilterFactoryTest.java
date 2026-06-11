package com.example.gw.policy;

import com.example.gw.model.PolicyResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SecurityPolicyGatewayFilterFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecurityPolicyGatewayFilterFactory factory;
    private RSAKey rsaKey;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        factory = new SecurityPolicyGatewayFilterFactory(objectMapper);
    }

    // ── 동작 L: allowList에 있는 IP는 통과한다 ────────────────────────────
    @Test
    void allowList에_있는_IP는_요청을_통과시킨다() throws Exception {
        var policy = policyWithIpFilter("""
                {"allowList":["10.0.0.0/8","192.168.1.0/24"]}""");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").remoteAddress(
                        new java.net.InetSocketAddress("10.0.0.5", 0)).build());
        var chain = mockChain();

        GatewayFilter filter = factory.apply(config(policy));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    // ── 동작 M: allowList에 없는 IP는 403을 반환한다 ──────────────────────
    @Test
    void allowList에_없는_IP는_403을_반환한다() throws Exception {
        var policy = policyWithIpFilter("""
                {"allowList":["10.0.0.0/8"]}""");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").remoteAddress(
                        new java.net.InetSocketAddress("172.16.0.1", 0)).build());
        var chain = mockChain();

        GatewayFilter filter = factory.apply(config(policy));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    // ── 동작 N: 유효한 JWT는 통과하고 claims를 헤더로 주입한다 ──────────────
    @Test
    void 유효한_JWT는_통과하고_claimsToHeaders를_주입한다() throws Exception {
        String token = signedJwt(
                new JWTClaimsSet.Builder()
                        .issuer("https://auth.example.com")
                        .subject("user-123")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());

        var policy = policyWithJwt(rsaKey.toPublicJWK().toJSONString());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "Bearer " + token)
                        .build());
        var chain = mockChain();

        GatewayFilter filter = factory.apply(config(policy));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getRequest().getHeaders().getFirst("X-User-ID")).isEqualTo("user-123");
    }

    // ── 동작 O: Authorization 헤더 없으면 401 ─────────────────────────────
    @Test
    void Authorization_헤더_없으면_401을_반환한다() throws Exception {
        var policy = policyWithJwt(rsaKey.toPublicJWK().toJSONString());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").build());
        var chain = mockChain();

        GatewayFilter filter = factory.apply(config(policy));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 동작 P: 만료된 JWT는 401을 반환한다 ───────────────────────────────
    @Test
    void 만료된_JWT는_401을_반환한다() throws Exception {
        String token = signedJwt(
                new JWTClaimsSet.Builder()
                        .issuer("https://auth.example.com")
                        .subject("user-123")
                        .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                        .build());

        var policy = policyWithJwt(rsaKey.toPublicJWK().toJSONString());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "Bearer " + token)
                        .build());
        var chain = mockChain();

        GatewayFilter filter = factory.apply(config(policy));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private GatewayFilterChain mockChain() {
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private SecurityPolicyGatewayFilterFactory.Config config(PolicyResource policy) {
        var config = new SecurityPolicyGatewayFilterFactory.Config();
        config.setPolicyResource(policy);
        return config;
    }

    private PolicyResource policyWithIpFilter(String ipFilterJson) throws Exception {
        return policyWithConfig("{\"ipFilter\":" + ipFilterJson + "}");
    }

    private PolicyResource policyWithJwt(String publicKeyJson) throws Exception {
        String jwtConfig = String.format("""
                {"jwtValidation":{"issuer":"https://auth.example.com","publicKey":%s,
                "claimsToHeaders":{"sub":"X-User-ID"}}}""", publicKeyJson);
        return policyWithConfig(jwtConfig);
    }

    private PolicyResource policyWithConfig(String configJson) throws Exception {
        var policy = new PolicyResource();
        policy.getMetadata().setName("test-policy");
        policy.setType("Security");
        var targetRef = new PolicyResource.TargetRef();
        targetRef.setName("some-router");
        policy.getSpec().setTargetRef(targetRef);
        policy.getSpec().setConfig(objectMapper.readTree(configJson));
        return policy;
    }

    private String signedJwt(JWTClaimsSet claims) throws Exception {
        var signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        signedJWT.sign(new RSASSASigner(rsaKey));
        return signedJWT.serialize();
    }
}

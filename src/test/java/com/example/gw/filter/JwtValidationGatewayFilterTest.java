package com.example.gw.filter;

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

class JwtValidationGatewayFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RSAKey rsaKey;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    }

    private GatewayFilterChain mockChain() {
        var chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private JwtValidationGatewayFilter filterWith(RSAKey publicKey, Map<String, String> claimsToHeaders) throws Exception {
        return new JwtValidationGatewayFilter(objectMapper.readTree(publicKey.toJSONString()), claimsToHeaders);
    }

    private String signedJwt(JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    @Test
    void 유효한_JWT는_통과하고_claimsToHeaders를_주입한다() throws Exception {
        String token = signedJwt(new JWTClaimsSet.Builder()
                .subject("user-123")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build());

        var filter = filterWith(rsaKey.toPublicJWK(), Map.of("sub", "X-User-ID"));
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "Bearer " + token)
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getRequest().getHeaders().getFirst("X-User-ID")).isEqualTo("user-123");
    }

    @Test
    void Authorization_헤더_없으면_401을_반환한다() throws Exception {
        var filter = filterWith(rsaKey.toPublicJWK(), Map.of());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api").build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void 만료된_JWT는_401을_반환한다() throws Exception {
        String token = signedJwt(new JWTClaimsSet.Builder()
                .subject("user-123")
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        var filter = filterWith(rsaKey.toPublicJWK(), Map.of());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "Bearer " + token)
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 잘못된_서명의_JWT는_401을_반환한다() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).generate();
        String token = signedJwt(new JWTClaimsSet.Builder()
                .subject("user-123")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build());

        var filter = filterWith(otherKey.toPublicJWK(), Map.of());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api")
                        .header("Authorization", "Bearer " + token)
                        .build());
        var chain = mockChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

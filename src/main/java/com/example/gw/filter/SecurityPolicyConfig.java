package com.example.gw.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityPolicyConfig {

    private IpFilter ipFilter;
    private JwtValidation jwtValidation;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpFilter {
        private List<String> allowList = List.of();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JwtValidation {
        private String issuer;
        private JsonNode publicKey;
        private Map<String, String> claimsToHeaders = Map.of();
    }
}

package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayResource {
    private String apiVersion = "";
    private String kind = "Gateway";
    private ResourceMetadata metadata = new ResourceMetadata();
    private Spec spec = new Spec();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {
        private GlobalPolicy globalPolicy;
        private Logging logging = new Logging();
        private Tracing tracing;
        private Metrics metrics;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalPolicy {
        private IpFilter ipFilter;
        private RateLimit rateLimit;
        private Cors cors;
        private String maxRequestBodySize;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpFilter {
        private List<String> denyList = List.of();
        private List<String> allowList = List.of();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RateLimit {
        private int requestsPerSecond;
        private int burst;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cors {
        private List<String> allowOrigins = List.of();
        private List<String> allowMethods = List.of();
        private List<String> allowHeaders = List.of();
        private List<String> exposeHeaders = List.of();
        private boolean allowCredentials;
        private int maxAge = 3600;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Logging {
        private AccessLog accessLog = new AccessLog();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessLog {
        private boolean enabled = true;
        private String format = "JSON";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tracing {
        private boolean enabled;
        private double samplingRate = 0.1;
        private String endpoint;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metrics {
        private boolean enabled;
        private String path = "/metrics";
        private Integer port;
    }
}

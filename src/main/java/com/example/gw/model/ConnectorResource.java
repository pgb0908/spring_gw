package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectorResource {
    private String apiVersion = "";
    private String kind = "Connector";
    private String uid = "";
    private String workspaceId = "";
    private String id = "";
    private String name = "";
    private String version = "";
    private String description = "";
    private ResourceMetadata metadata = new ResourceMetadata();
    private Spec spec = new Spec();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {
        private String protocol = "HTTP";
        private String proxyPath = "";
        private String method = "POST";
        private MessageTemplate requestMsgTpl;
        private MessageTemplate responseMsgTpl;
        private UpstreamTls upstreamTls;
        private String maxRequestBodySize;
        private String maxResponseBodySize;
        private LoadBalancing loadBalancing = new LoadBalancing();
        private HealthCheck healthCheck;
        private RetryPolicy retry;
        private CircuitBreaker circuitBreaker;
        private TimeoutPolicy timeout;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UpstreamTls {
        private boolean enabled;
        private String sni;
        private String caRef;
        private String clientCertRef;
        private String clientKeyRef;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoadBalancing {
        private LbAlgorithm algorithm = LbAlgorithm.ROUND_ROBIN;
        private List<Target> targets = List.of();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageTemplate {
        private String uid = "";
        private String id = "";
        private String name = "";
    }

    public enum LbAlgorithm { ROUND_ROBIN, LEAST_CONN, IP_HASH, RANDOM }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {
        private String host = "";
        private int port = 80;
        private int weight = 1;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HealthCheck {
        private String path = "/";
        private String interval;
        private String timeout;
        private Integer healthyThreshold;
        private Integer unhealthyThreshold;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RetryPolicy {
        private List<String> retryOn = List.of("5xx", "connect-failure");
        private int numRetries = 3;
        private String perTryTimeout;
        private RetryBackoff retryBackoff;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RetryBackoff {
        private String baseInterval = "100ms";
        private String maxInterval = "1s";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CircuitBreaker {
        private Integer maxConnections;
        private Integer minRequestAmount;
        private Integer failureThreshold;
        private String resetTimeout;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeoutPolicy {
        private String connect;
        private String read;
        private String send;
    }
}

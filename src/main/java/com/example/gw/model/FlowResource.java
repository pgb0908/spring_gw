package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlowResource {
    private String apiVersion = "";
    private String kind = "Flow";
    private ResourceMetadata metadata = new ResourceMetadata();
    private Spec spec = new Spec();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {
        private String protocol = "gRPC";
        private ConnectorResource.UpstreamTls upstreamTls;
        private LoadBalancing loadBalancing = new LoadBalancing();
        private ConnectorResource.TimeoutPolicy timeout;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoadBalancing {
        private ConnectorResource.LbAlgorithm algorithm = ConnectorResource.LbAlgorithm.ROUND_ROBIN;
        private List<Target> targets = List.of();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {
        private String host = "";
        private int port = 80;
        @JsonProperty("flow-id")
        private String flowId = "";
    }
}

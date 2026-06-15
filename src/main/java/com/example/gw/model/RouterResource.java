package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouterResource {
    private String apiVersion = "";
    private String kind = "Router";
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
        private Rule rule = new Rule();
        private List<Destination> destinations = List.of();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        private String protocol = "HTTP";
        private Match match = new Match();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Match {
        private String path = "/";
        private String methods = "GET";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Destination {
        private DestinationRef destinationRef = new DestinationRef();
        private Integer weight;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DestinationRef {
        private DestinationKind kind = DestinationKind.Connector;
        private String uid = "";
        private String id = "";
        private String name = "";
    }

    public enum DestinationKind { Connector, Flow }
}

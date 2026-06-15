package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ListenerResource {
    private String apiVersion = "";
    private String kind = "Listener";
    private ResourceMetadata metadata = new ResourceMetadata();
    private Spec spec = new Spec();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {
        private Protocol protocol = Protocol.HTTP;
        private Role role = Role.INGRESS;
        private int port = 8080;
        private String host = "0.0.0.0";
        private List<String> allowedHostnames = List.of();
        private Tls tls;
        private Connection connection;
    }

    public enum Protocol { HTTP, HTTPS, TCP, GRPC }

    public enum Role { INGRESS, EGRESS }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tls {
        private TlsMode mode = TlsMode.TERMINATE;
        private String minVersion = "1.2";
        private List<Certificate> certificates = List.of();
    }

    public enum TlsMode { TERMINATE, PASSTHROUGH }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Certificate {
        private String certRef = "";
        private String keyRef = "";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Connection {
        private String readTimeout;
        private String writeTimeout;
        private Integer maxConnections;
    }
}

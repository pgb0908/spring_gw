package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FlowEnvelope {
    private String guid;
    private String status;
    @JsonProperty("error_code")
    private String errorCode;
    @JsonProperty("error_message")
    private String errorMessage;
    @JsonProperty("flow_id")
    private String flowId;
    @JsonProperty("flow_version")
    private Integer flowVersion;
    @JsonProperty("gateway_id")
    private String gatewayId;
    @JsonProperty("core_id")
    private String coreId;
    @JsonProperty("started_at")
    private Long startedAt;
    @JsonProperty("finished_at")
    private Long finishedAt;
    private Integer timeout;
    @JsonProperty("ingress_gateway_id")
    private String ingressGatewayId;
    @JsonProperty("connector_id")
    private String connectorId;
    @JsonProperty("node_id")
    private String nodeId;
    @JsonProperty("node_type")
    private String nodeType;
    private String action;
    private String payload;
    private String charset;
    @JsonProperty("content_type")
    private String contentType;
    private Map<String, String> header;
}

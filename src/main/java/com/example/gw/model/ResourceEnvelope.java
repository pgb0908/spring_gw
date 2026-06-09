package com.example.gw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceEnvelope {
    private String apiVersion = "";
    private String kind = "";
    private ResourceMetadata metadata = new ResourceMetadata();
    private JsonNode spec;
}

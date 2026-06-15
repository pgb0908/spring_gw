package com.example.gw.egress;

import com.example.gw.model.FlowEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpMethod;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ConnectorCallExecutor {

    private final WebClient webClient;

    /**
     * 백엔드 URL로 CONNECTOR_REQUEST를 전달하고 CONNECTOR_RESPONSE FlowEnvelope를 반환한다.
     * payload는 base64 디코딩하여 HTTP 본문으로 전송하고, 응답 본문은 다시 base64로 인코딩한다.
     */
    public Mono<FlowEnvelope> execute(String backendBaseUrl, String proxyPath, String method, FlowEnvelope request) {
        String url = backendBaseUrl + (proxyPath != null && !proxyPath.isBlank() ? proxyPath : "");
        HttpMethod httpMethod = parseMethod(method);
        byte[] payloadBytes = decodePayload(request.getPayload());

        return webClient.method(httpMethod)
                .uri(url)
                .headers(h -> {
                    if (request.getHeader() != null) request.getHeader().forEach(h::set);
                })
                .bodyValue(payloadBytes)
                .exchangeToMono(response -> response.toEntity(byte[].class))
                .map(entity -> buildResponse(request, entity.getStatusCode().value(),
                        entity.getBody(), entity.getHeaders().toSingleValueMap()))
                .doOnError(e -> log.error("백엔드 호출 실패 — url={}, guid={}: {}", url, request.getGuid(), e.getMessage()));
    }

    private HttpMethod parseMethod(String method) {
        if (method == null || method.isBlank()) return HttpMethod.POST;
        try {
            return HttpMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 HTTP method '{}' — POST로 폴백", method);
            return HttpMethod.POST;
        }
    }

    private FlowEnvelope buildResponse(FlowEnvelope req, int httpStatus,
                                       byte[] body, Map<String, String> responseHeaders) {
        FlowEnvelope resp = new FlowEnvelope();
        resp.setGuid(req.getGuid());
        resp.setFlowId(req.getFlowId());
        resp.setFlowVersion(req.getFlowVersion());
        resp.setCoreId(req.getCoreId());
        resp.setIngressGatewayId(req.getIngressGatewayId());
        resp.setNodeId(req.getNodeId());
        resp.setNodeType(req.getNodeType());
        resp.setStartedAt(req.getStartedAt());
        resp.setGatewayId(req.getGatewayId());
        resp.setConnectorId(req.getConnectorId());
        resp.setFinishedAt(System.currentTimeMillis());
        resp.setAction("CONNECTOR_RESPONSE");

        if (httpStatus >= 200 && httpStatus < 300) {
            resp.setStatus("RUNNING");
            byte[] responseBody = body != null ? body : new byte[0];
            resp.setPayload(Base64.getEncoder().encodeToString(responseBody));
            resp.setHeader(new HashMap<>(responseHeaders));
        } else {
            resp.setStatus("ERROR");
            resp.setErrorCode("BACKEND_ERROR");
            resp.setErrorMessage("Backend returned HTTP " + httpStatus);
        }
        return resp;
    }

    private byte[] decodePayload(String payload) {
        if (payload == null || payload.isBlank()) return new byte[0];
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            log.warn("payload base64 디코딩 실패 — raw bytes로 처리");
            return payload.getBytes();
        }
    }
}

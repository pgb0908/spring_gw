package com.example.gw.flow;

import com.google.protobuf.ByteString;
import com.tmax.iip.common.grpc.runtime.v1.*;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class FlowGatewayFilter implements GlobalFilter, Ordered {

    static final String FLOW_ID_HEADER = "X-Flow-Id";

    private final Map<String, GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub> flowStubs;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String flowId = exchange.getRequest().getHeaders().getFirst(FLOW_ID_HEADER);
        if (flowId == null || !flowStubs.containsKey(flowId)) {
            return chain.filter(exchange);
        }

        GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub stub = flowStubs.get(flowId);

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().allocateBuffer(0))
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    String contentType = exchange.getRequest().getHeaders().getContentType() != null
                            ? exchange.getRequest().getHeaders().getContentType().toString()
                            : MediaType.APPLICATION_OCTET_STREAM_VALUE;

                    String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
                    if (traceId == null) {
                        traceId = UUID.randomUUID().toString();
                    }

                    ExecuteFlowRequest request = ExecuteFlowRequest.newBuilder()
                            .setFlowId(flowId)
                            .setHeader(RuntimeHeader.newBuilder()
                                    .setRequestId(UUID.randomUUID().toString())
                                    .setTraceId(traceId)
                                    .build())
                            .setPayload(RuntimePayload.newBuilder()
                                    .setBody(ByteString.copyFrom(bodyBytes))
                                    .setContentType(contentType)
                                    .build())
                            .build();

                    ExecuteFlowResponse response;
                    try {
                        response = stub.executeFlow(request);
                    } catch (StatusRuntimeException e) {
                        log.error("gRPC call failed for flow '{}': {}", flowId, e.getStatus());
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                        return exchange.getResponse().setComplete();
                    }

                    if (response.hasError()) {
                        RuntimeError error = response.getError();
                        log.warn("Flow '{}' returned error: code={} message={}", flowId, error.getCode(), error.getMessage());
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode());
                        exchange.getResponse().setStatusCode(status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR);
                        byte[] errorBody = error.getMessage().getBytes();
                        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(errorBody);
                        return exchange.getResponse().writeWith(Mono.just(buffer));
                    }

                    HttpStatus status = HttpStatus.resolve(response.getStatusCode());
                    exchange.getResponse().setStatusCode(status != null ? status : HttpStatus.OK);

                    byte[] responseBody = response.getPayload().getBody().toByteArray();
                    String responseContentType = response.getPayload().getContentType();
                    if (!responseContentType.isBlank()) {
                        exchange.getResponse().getHeaders().setContentType(MediaType.parseMediaType(responseContentType));
                    }

                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(responseBody);
                    return exchange.getResponse().writeWith(Mono.just(buffer));
                });
    }

    @Override
    public int getOrder() {
        // before routing filters
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}

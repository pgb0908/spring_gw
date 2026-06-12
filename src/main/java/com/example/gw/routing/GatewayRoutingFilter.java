package com.example.gw.routing;

import com.example.gw.model.RouterResource;
import com.google.protobuf.ByteString;
import com.tmax.iip.common.grpc.runtime.v1.*;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
public class GatewayRoutingFilter implements GlobalFilter, Ordered {

    private final Map<String, GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub> flowStubs;

    public GatewayRoutingFilter(Map<String, GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub> flowStubs) {
        this.flowStubs = flowStubs;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String destinationKind = (String) route.getMetadata().get("destinationKind");
        if (RouterResource.DestinationKind.Flow.name().equals(destinationKind)) {
            ServerWebExchangeUtils.setAlreadyRouted(exchange);
            return routeToFlow(exchange, route);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> routeToFlow(ServerWebExchange exchange, Route route) {
        String flowId = (String) route.getMetadata().get("flowId");
        if (flowId == null) {
            log.warn("Flow route '{}' has no flowId in metadata", route.getId());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }

        GatewayCoreServiceGrpc.GatewayCoreServiceBlockingStub stub = flowStubs.get(flowId);
        if (stub == null) {
            log.warn("No gRPC stub registered for flowId '{}' — route '{}'", flowId, route.getId());
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return exchange.getResponse().setComplete();
        }

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
                    if (traceId == null) traceId = UUID.randomUUID().toString();

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
                        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(error.getMessage().getBytes());
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
}

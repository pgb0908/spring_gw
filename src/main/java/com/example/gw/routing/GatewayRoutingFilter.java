package com.example.gw.routing;

import com.example.gw.gateway.RequestContext;
import com.example.gw.model.RouterResource;
import com.google.protobuf.ByteString;
import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class GatewayRoutingFilter implements GlobalFilter, Ordered {

    private final Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs;

    public GatewayRoutingFilter(Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs) {
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
            log.warn("Flow 라우트 '{}' metadata에 flowId 없음", route.getId());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }

        CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub stub = flowStubs.get(flowId);
        if (stub == null) {
            log.warn("flowId '{}'에 등록된 gRPC stub 없음 — 라우트 '{}'", flowId, route.getId());
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return exchange.getResponse().setComplete();
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().allocateBuffer(0))
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    // RequestContextFilter가 심어둔 traceId/requestedAt 사용
                    RequestContext ctx = exchange.getAttribute(RequestContext.ATTR_KEY);
                    String guid = (ctx != null) ? ctx.getTraceId() : UUID.randomUUID().toString();
                    long startedAt = (ctx != null)
                            ? ctx.getRequestedAt().toEpochMilli()
                            : Instant.now().toEpochMilli();

                    String contentType = exchange.getRequest().getHeaders().getContentType() != null
                            ? exchange.getRequest().getHeaders().getContentType().toString()
                            : MediaType.APPLICATION_OCTET_STREAM_VALUE;

                    GatewayCoreEnvelope envelope = GatewayCoreEnvelope.newBuilder()
                            .setGuid(guid)
                            .setFlowId(flowId)
                            .setStartedAt(startedAt)
                            .setAction(GatewayCoreAction.START_REQUEST)
                            .setPayload(ByteString.copyFrom(bodyBytes))
                            .setContentType(contentType)
                            .build();

                    GatewayCoreAck ack;
                    try {
                        ack = stub.startFlow(envelope);
                    } catch (StatusRuntimeException e) {
                        log.error("gRPC StartFlow 실패 — flowId='{}': {}", flowId, e.getStatus());
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                        return exchange.getResponse().setComplete();
                    }

                    if (ack.getStatus() == GatewayCoreStatus.ERROR
                            || ack.getStatus() == GatewayCoreStatus.FAILED) {
                        log.warn("Flow '{}' 오류 응답 — status={} errorCode={} message={}",
                                flowId, ack.getStatus(), ack.getErrorCode(), ack.getErrorMessage());
                        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                        if (!ack.getErrorMessage().isBlank()) {
                            DataBuffer buffer = exchange.getResponse().bufferFactory()
                                    .wrap(ack.getErrorMessage().getBytes());
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        }
                        return exchange.getResponse().setComplete();
                    }

                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().setComplete();
                });
    }
}

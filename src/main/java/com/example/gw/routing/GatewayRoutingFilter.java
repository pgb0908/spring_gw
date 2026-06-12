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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class GatewayRoutingFilter implements GlobalFilter, Ordered {

    private final Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs;
    private final PendingResponseRegistry pendingResponseRegistry;

    public GatewayRoutingFilter(
            Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs,
            PendingResponseRegistry pendingResponseRegistry) {
        this.flowStubs = flowStubs;
        this.pendingResponseRegistry = pendingResponseRegistry;
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

                    // SendResponse 도착을 기다릴 sink를 StartFlow 호출 전에 등록한다.
                    // (StartFlow 응답 전에 SendResponse가 먼저 도착하는 race condition 방지)
                    Sinks.One<GatewayCoreEnvelope> responseSink = Sinks.one();
                    pendingResponseRegistry.register(guid, responseSink);

                    GatewayCoreAck ack;
                    try {
                        ack = stub.startFlow(envelope);
                    } catch (StatusRuntimeException e) {
                        log.error("gRPC StartFlow 실패 — flowId='{}': {}", flowId, e.getStatus());
                        pendingResponseRegistry.error(guid, e);
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                        return exchange.getResponse().setComplete();
                    }

                    if (ack.getStatus() == GatewayCoreStatus.ERROR
                            || ack.getStatus() == GatewayCoreStatus.FAILED) {
                        log.warn("StartFlow 거부 — flowId='{}' status={} message={}",
                                flowId, ack.getStatus(), ack.getErrorMessage());
                        pendingResponseRegistry.error(guid,
                                new RuntimeException(ack.getErrorMessage()));
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                        return exchange.getResponse().setComplete();
                    }

                    // StartFlow가 RECEIVED를 반환하면 SendResponse를 기다린다.
                    return responseSink.asMono()
                            .flatMap(responseEnvelope ->
                                    writeHttpResponse(exchange, responseEnvelope, stub, flowId))
                            .onErrorResume(e -> {
                                log.error("Flow 응답 처리 실패 — guid={}: {}", guid, e.getMessage());
                                exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                                return exchange.getResponse().setComplete();
                            });
                });
    }

    /**
     * SendResponse로 받은 envelope를 HTTP 응답으로 변환하고,
     * 이후 CoreRuntimeService.ReportResponseResult()를 호출한다.
     */
    private Mono<Void> writeHttpResponse(
            ServerWebExchange exchange,
            GatewayCoreEnvelope envelope,
            CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub stub,
            String flowId) {

        if (envelope.getStatus() == GatewayCoreStatus.ERROR
                || envelope.getStatus() == GatewayCoreStatus.FAILED) {
            log.warn("Flow 오류 응답 — flowId='{}' errorCode={} message={}",
                    flowId, envelope.getErrorCode(), envelope.getErrorMessage());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            Mono<Void> writeBody = envelope.getErrorMessage().isBlank()
                    ? exchange.getResponse().setComplete()
                    : exchange.getResponse().writeWith(
                            Mono.just(exchange.getResponse().bufferFactory()
                                    .wrap(envelope.getErrorMessage().getBytes())));
            return writeBody.doOnTerminate(() -> fireReportResponseResult(stub, envelope, flowId));
        }

        exchange.getResponse().setStatusCode(HttpStatus.OK);
        if (!envelope.getContentType().isBlank()) {
            exchange.getResponse().getHeaders()
                    .setContentType(MediaType.parseMediaType(envelope.getContentType()));
        }

        byte[] responseBody = envelope.getPayload().toByteArray();
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(responseBody);
        return exchange.getResponse().writeWith(Mono.just(buffer))
                .doOnTerminate(() -> fireReportResponseResult(stub, envelope, flowId));
    }

    /**
     * HTTP 응답 전송 완료 후 Flow 엔진에 결과를 보고한다.
     * HTTP 응답이 완전히 전송된 뒤 연결이 닫혀도 실행되도록 별도 스케줄러에서 fire-and-forget으로 수행한다.
     * 실패해도 이미 HTTP 응답은 전송된 상태이므로 에러를 로그로만 남긴다.
     */
    private void fireReportResponseResult(
            CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub stub,
            GatewayCoreEnvelope envelope,
            String flowId) {
        Mono.fromRunnable(() -> {
            try {
                stub.reportResponseResult(envelope);
                log.debug("ReportResponseResult 완료 — guid={}", envelope.getGuid());
            } catch (StatusRuntimeException e) {
                log.error("ReportResponseResult 실패 — flowId='{}': {}", flowId, e.getStatus());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }
}

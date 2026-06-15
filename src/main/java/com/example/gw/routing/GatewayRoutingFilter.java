package com.example.gw.routing;

import com.example.gw.gateway.RequestContext;
import com.example.gw.model.FlowEnvelope;
import com.example.gw.model.RouterResource;
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

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
public class GatewayRoutingFilter implements GlobalFilter, Ordered {

    private final CoreHttpClient coreHttpClient;
    private final PendingResponseRegistry pendingResponseRegistry;

    public GatewayRoutingFilter(CoreHttpClient coreHttpClient, PendingResponseRegistry pendingResponseRegistry) {
        this.coreHttpClient = coreHttpClient;
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

                    FlowEnvelope envelope = new FlowEnvelope();
                    envelope.setGuid(guid);
                    envelope.setStatus("RECEIVED");
                    envelope.setFlowId(flowId);
                    envelope.setStartedAt(startedAt);
                    envelope.setAction("START_REQUEST");
                    envelope.setPayload(Base64.getEncoder().encodeToString(bodyBytes));
                    envelope.setContentType(contentType);

                    // SendResponse 도착 전에 sink를 먼저 등록해야 race condition을 방지한다.
                    Sinks.One<FlowEnvelope> responseSink = Sinks.one();
                    pendingResponseRegistry.register(guid, responseSink);

                    return coreHttpClient.postStartFlow(flowId, envelope)
                            .flatMap(ack -> {
                                if ("ERROR".equals(ack.getStatus()) || "FAILED".equals(ack.getStatus())) {
                                    log.warn("StartFlow 거부 — flowId='{}' status={} message={}",
                                            flowId, ack.getStatus(), ack.getErrorMessage());
                                    pendingResponseRegistry.error(guid,
                                            new RuntimeException(ack.getErrorMessage()));
                                    exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                                    return exchange.getResponse().setComplete();
                                }
                                return responseSink.asMono()
                                        .flatMap(responseEnvelope ->
                                                writeHttpResponse(exchange, responseEnvelope, flowId))
                                        .onErrorResume(e -> {
                                            log.error("Flow 응답 처리 실패 — guid={}: {}", guid, e.getMessage());
                                            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                                            return exchange.getResponse().setComplete();
                                        });
                            })
                            .onErrorResume(e -> {
                                log.error("StartFlow 실패 — flowId='{}': {}", flowId, e.getMessage());
                                pendingResponseRegistry.error(guid, e);
                                exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                                return exchange.getResponse().setComplete();
                            });
                });
    }

    private Mono<Void> writeHttpResponse(ServerWebExchange exchange, FlowEnvelope envelope, String flowId) {
        if ("ERROR".equals(envelope.getStatus()) || "FAILED".equals(envelope.getStatus())) {
            log.warn("Flow 오류 응답 — flowId='{}' errorCode={} message={}",
                    flowId, envelope.getErrorCode(), envelope.getErrorMessage());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            String msg = envelope.getErrorMessage() != null ? envelope.getErrorMessage() : "";
            Mono<Void> writeBody = msg.isBlank()
                    ? exchange.getResponse().setComplete()
                    : exchange.getResponse().writeWith(
                            Mono.just(exchange.getResponse().bufferFactory().wrap(msg.getBytes())));
            return writeBody.doOnTerminate(() -> coreHttpClient.postResponseAckAsync(flowId, envelope));
        }

        exchange.getResponse().setStatusCode(HttpStatus.OK);
        if (envelope.getContentType() != null && !envelope.getContentType().isBlank()) {
            exchange.getResponse().getHeaders()
                    .setContentType(MediaType.parseMediaType(envelope.getContentType()));
        }

        byte[] responseBody = envelope.getPayload() != null
                ? Base64.getDecoder().decode(envelope.getPayload())
                : new byte[0];
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(responseBody);
        return exchange.getResponse().writeWith(Mono.just(buffer))
                .doOnTerminate(() -> coreHttpClient.postResponseAckAsync(flowId, envelope));
    }
}

package com.example.gw.routing;

import com.example.gw.model.FlowEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class InMemoryPendingResponseRegistry implements PendingResponseRegistry {

    private final ConcurrentMap<String, Sinks.One<FlowEnvelope>> pending = new ConcurrentHashMap<>();

    @Override
    public void register(String guid, Sinks.One<FlowEnvelope> sink) {
        pending.put(guid, sink);
        log.debug("대기 등록 — guid={}, 현재 대기 수={}", guid, pending.size());
    }

    @Override
    public void complete(String guid, FlowEnvelope envelope) {
        Sinks.One<FlowEnvelope> sink = pending.remove(guid);
        if (sink == null) {
            log.warn("완료 대상 없음 — guid={} (이미 완료됐거나 타임아웃)", guid);
            return;
        }
        sink.tryEmitValue(envelope);
        log.debug("대기 완료 — guid={}", guid);
    }

    @Override
    public void error(String guid, Throwable t) {
        Sinks.One<FlowEnvelope> sink = pending.remove(guid);
        if (sink == null) {
            log.warn("에러 대상 없음 — guid={}", guid);
            return;
        }
        sink.tryEmitError(t);
        log.debug("대기 에러 — guid={}", guid);
    }
}

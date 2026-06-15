package com.example.gw.routing;

import com.example.gw.model.FlowEnvelope;
import reactor.core.publisher.Sinks;

public interface PendingResponseRegistry {

    /** guid로 대기 sink를 등록한다. StartFlow 호출 전에 반드시 먼저 등록해야 한다. */
    void register(String guid, Sinks.One<FlowEnvelope> sink);

    /** ResponseRequest 수신 시 guid로 sink를 꺼내 응답을 완료한다. */
    void complete(String guid, FlowEnvelope envelope);

    /** StartFlow 실패 시 guid로 sink를 꺼내 에러를 전파한다. */
    void error(String guid, Throwable t);
}

package com.example.gw.routing;

import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.GatewayCoreEnvelope;
import reactor.core.publisher.Sinks;

public interface PendingResponseRegistry {

    /** guid로 대기 sink를 등록한다. StartFlow 호출 전에 반드시 먼저 등록해야 한다. */
    void register(String guid, Sinks.One<GatewayCoreEnvelope> sink);

    /** SendResponse 수신 시 guid로 sink를 꺼내 응답을 완료한다. */
    void complete(String guid, GatewayCoreEnvelope envelope);

    /** StartFlow 실패 시 guid로 sink를 꺼내 에러를 전파한다. */
    void error(String guid, Throwable t);
}

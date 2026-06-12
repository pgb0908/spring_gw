package com.example.gw.routing;

import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class GatewayRuntimeServiceImpl extends GatewayRuntimeServiceGrpc.GatewayRuntimeServiceImplBase {

    private final PendingResponseRegistry pendingResponseRegistry;

    /**
     * Flow 엔진이 플로우 실행 결과를 게이트웨이로 전달하는 콜백.
     * guid로 대기 중인 HTTP 응답 sink를 찾아 완료시킨다.
     * HTTP 응답 전송 및 ReportResponseResult 호출은 GatewayRoutingFilter가 담당한다.
     */
    @Override
    public void sendResponse(GatewayCoreEnvelope envelope, StreamObserver<GatewayCoreAck> responseObserver) {
        log.info("▶ SendResponse 수신 — guid={}, flowId={}, status={}",
                envelope.getGuid(), envelope.getFlowId(), envelope.getStatus());
        log.info("  contentType = {}", envelope.getContentType());
        log.info("  body        = {}", envelope.getPayload().toStringUtf8());

        pendingResponseRegistry.complete(envelope.getGuid(), envelope);

        responseObserver.onNext(GatewayCoreAck.newBuilder()
                .setGuid(envelope.getGuid())
                .setStatus(GatewayCoreStatus.RECEIVED)
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Flow 엔진이 connector 처리 요청을 전달하는 콜백 (현재 미구현).
     */
    @Override
    public void sendRequest(GatewayCoreEnvelope envelope, StreamObserver<GatewayCoreAck> responseObserver) {
        log.warn("SendRequest 수신 — 미구현 (guid={})", envelope.getGuid());
        responseObserver.onNext(GatewayCoreAck.newBuilder()
                .setGuid(envelope.getGuid())
                .setStatus(GatewayCoreStatus.RECEIVED)
                .build());
        responseObserver.onCompleted();
    }
}

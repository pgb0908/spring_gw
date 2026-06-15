# Gateway-Flow 간 비동기 요청-응답 분리 (StartFlow / SendResponse)

게이트웨이와 Flow 엔진 간의 통신을 `StartFlow`(요청)와 `SendResponse`(응답)로 분리하는 비동기 패턴을 채택한다. `StartFlow`는 즉시 `RECEIVED` ACK를 반환하고, 실제 응답은 Flow 엔진이 게이트웨이로 역방향 gRPC 호출(`GatewayRuntimeService.SendResponse`)을 통해 전달한다.

## Considered Options

- **StartFlow 블로킹 방식**: `CoreRuntimeService.StartFlow()`가 플로우 실행 완료까지 블로킹한 뒤 결과를 반환. 구현이 단순하지만, Flow 실행 시간이 HTTP 타임아웃을 초과할 경우 연결이 끊기고 게이트웨이가 응답을 처리할 수 없다.
- **StartFlow + SendResponse 비동기 분리** ← 선택: `StartFlow`는 수신 확인(RECEIVED)만 반환하고, Flow 엔진이 실행 완료 후 게이트웨이의 `GatewayRuntimeService.SendResponse()`를 역호출한다. HTTP 응답 지연 시간이 Flow 실행 시간과 독립적이며, 게이트웨이는 응답을 수신한 뒤 HTTP 클라이언트에 전달한다.

## 설계

```
HTTP 클라이언트
  → GatewayRoutingFilter
      1. PendingResponseRegistry에 guid → Sinks.One 등록
      2. CoreRuntimeService.StartFlow(GatewayCoreEnvelope{guid, flowId, payload, ...})
         ← GatewayCoreAck{RECEIVED} 즉시 반환
      3. responseSink.asMono() 대기

Flow 엔진
  → GatewayRuntimeService.SendResponse(GatewayCoreEnvelope{guid, payload, status})
      4. PendingResponseRegistry.complete(guid) → sink 방출
  ← GatewayCoreAck{RECEIVED} 반환

GatewayRoutingFilter (재개)
      5. HTTP 응답 작성 (payload, content_type, status 변환)
      6. CoreRuntimeService.ReportResponseResult(envelope) [fire-and-forget]
```

`guid`는 `RequestContext.traceId`에서 가져오며, 클라이언트가 `X-Trace-Id` 헤더를 제공하면 그 값을 그대로 사용한다.

게이트웨이는 `GatewayRuntimeService` gRPC 서버를 `protocol=GRPC`인 Listener 설정을 통해 기동한다. HTTP Listener와 동일한 config 체계를 사용하며, GRPC Listener가 없으면 서버를 기동하지 않는다.

## Consequences

- HTTP 응답 대기(hold) 구현에 `Sinks.One`과 `PendingResponseRegistry`(ConcurrentHashMap)가 필요하다. 대기 중인 요청이 응답을 받지 못하면 메모리가 누수될 수 있으므로, 운영 환경에서는 타임아웃으로 sink를 강제 만료하는 처리가 필요하다.
- 게이트웨이가 gRPC 클라이언트(StartFlow, ReportResponseResult)인 동시에 gRPC 서버(SendResponse)로 동작한다. `StartFlow`를 서버 사이드 스트리밍(`returns (stream GatewayCoreEnvelope)`)으로 변경하면 게이트웨이가 별도 gRPC 서버를 띄울 필요가 없어지나, 이는 Flow 팀과의 외부 proto 계약이므로 게이트웨이 팀이 단독으로 변경할 수 없다.
- `ReportResponseResult`는 HTTP 응답 전송 직후 fire-and-forget으로 실행된다. 실패해도 HTTP 클라이언트는 이미 응답을 받은 상태이므로 로그로만 기록한다.

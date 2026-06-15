# Gateway-Flow 간 비동기 요청-응답 분리 (HTTP 기반)

게이트웨이와 Flow 엔진(Core) 간의 통신을 gRPC 대신 단순 HTTP로 처리한다. `StartFlow`(GW→Core), `ResponseRequest`(Core→GW), `ResponseAck`(GW→Core) 세 단계로 분리하는 비동기 패턴을 채택한다. `StartFlow`는 즉시 `RUNNING` ACK를 반환하고, 실제 응답은 Core가 GW로 역방향 HTTP 호출(`POST /gateway/ingress/response`)을 통해 전달한다.

## Considered Options

- **gRPC 블로킹 방식**: `CoreRuntimeService.StartFlow()`가 플로우 실행 완료까지 블로킹한 뒤 결과를 반환. 구현이 단순하지만 Flow 실행 시간이 HTTP 타임아웃을 초과할 경우 연결이 끊긴다.
- **gRPC StartFlow + SendResponse 비동기 분리**: `StartFlow`는 RECEIVED ACK만 반환하고, Core가 `GatewayRuntimeService.SendResponse()` gRPC 역호출로 결과를 전달. GW가 gRPC 클라이언트인 동시에 gRPC 서버로 동작해야 하는 복잡성이 있다.
- **HTTP StartFlow + ResponseRequest 비동기 분리** ← 선택: gRPC를 제거하고 모든 통신을 단순 HTTP로 통일. GW-Core 양방향 통신이 같은 `FlowEnvelope` JSON 포맷을 공유하며, Egress(Connector) 통신과도 동일한 구조를 사용한다.

## 설계

```
HTTP 클라이언트
  → GatewayRoutingFilter
      1. PendingResponseRegistry에 guid → Sinks.One 등록
      2. CoreHttpClient.postStartFlow(flowId, FlowEnvelope{guid, START_REQUEST, RECEIVED, payload, ...})
         ← FlowEnvelope{RUNNING} ACK 즉시 반환
      3. responseSink.asMono() 대기

Flow 엔진(Core)
  → POST /gateway/ingress/response (Egress Listener 포트)
         FlowEnvelope{guid, RESPONSE_REQUEST, payload, content_type, ...}
      4. PendingResponseRegistry.complete(guid) → sink 방출
  ← FlowEnvelope{RUNNING} ACK 반환

GatewayRoutingFilter (재개)
      5. HTTP 응답 작성 (payload, content_type, status 변환)
      6. CoreHttpClient.postResponseAck(flowId, FlowEnvelope{guid, RESPONSE_ACK}) [fire-and-forget]
```

**FlowEnvelope 필드 역할**:
- `guid`: 요청마다 유일한 식별자. `X-Trace-Id` 헤더 우선, 없으면 UUID 생성.
- `status`: StartFlow 요청 시 GW가 `RECEIVED`를 채워 보낸다. Core ACK는 `RUNNING`.
- `gateway_id`: GW 자신의 ID (`GatewayResource.metadata.name`).
- `action`: 메시지 종류 식별 (`START_REQUEST` / `RESPONSE_REQUEST` / `RESPONSE_ACK`).

**Core URL 결정**: `FlowResource.Target`의 `host:port`를 HTTP URL로 사용한다. `flow_id` → `http://{host}:{port}` 매핑을 `CoreHttpClient`가 관리한다.

**ResponseRequest 수신 엔드포인트**: Egress Listener(`role=EGRESS`) 포트에서 `POST /gateway/ingress/response`를 처리한다. 외부 클라이언트 트래픽(Ingress Listener)과 Core 내부 콜백(Egress Listener)을 포트 수준에서 분리한다.

## Consequences

- gRPC 의존성(`grpc-netty`, `grpc-stub`, `grpc-protobuf`, protobuf 생성 코드)을 제거할 수 있다. `FlowGrpcChannelConfig`, `GatewayGrpcServerConfig`, `GatewayRuntimeServiceImpl` 삭제.
- `PendingResponseRegistry`의 타입이 `Sinks.One<GatewayCoreEnvelope>` → `Sinks.One<FlowEnvelope>`으로 변경된다.
- `GatewayRoutingFilter`의 gRPC 블로킹 stub 호출이 WebClient 비동기 호출로 교체된다.
- 대기 중인 요청이 응답을 받지 못하면 메모리가 누수될 수 있으므로, 운영 환경에서는 타임아웃 기반 만료 처리가 필요하다 (현재 미구현).
- `ResponseAck`은 HTTP 응답 전송 직후 fire-and-forget으로 실행된다. 실패해도 HTTP 클라이언트는 이미 응답을 받은 상태이므로 로그로만 기록한다.

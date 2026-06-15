# 요청 처리 흐름

Spring Cloud Gateway가 HTTP 요청을 수신해 Flow 엔진으로 전달하고, 응답을 반환하기까지의 전체 흐름을 설명한다.

---

## 전체 개요

```
HTTP Client
    │
    ▼
[①] RequestContextFilter          traceId / requestedAt 생성 → exchange에 저장
    │
    ▼
[②] Route 매칭                    path / method → destinationKind, flowId 결정
    │
    ▼
[③] Policy GatewayFilter 체인     JWT 검증 / IP 필터 / API Key 인증 (라우트 설정 시)
    │
    ▼
[④] GatewayRoutingFilter
    ├─ destinationKind == Flow   → StartFlow gRPC 호출 (ADR-0008 비동기 흐름)
    └─ destinationKind == Connector → Netty HttpClient 위임
```

---

## 필터 실행 순서

| 순서 | 필터 | 종류 | Order |
|:---:|---|---|:---:|
| 1 | `RequestContextFilter` | GlobalFilter | `HIGHEST_PRECEDENCE` |
| 2 | SCG 내부 필터 (Route 매칭 등) | — | — |
| 3 | `JwtValidationGatewayFilter` | Per-Route GatewayFilter | 라우트 정의 순서 |
| 3 | `IpFilterGatewayFilter` | Per-Route GatewayFilter | 라우트 정의 순서 |
| 3 | `ApiKeyAuthGatewayFilter` | Per-Route GatewayFilter | 라우트 정의 순서 |
| 4 | `GatewayRoutingFilter` | GlobalFilter | `LOWEST_PRECEDENCE - 1` |
| 5 | `LoggingFilter` | GlobalFilter | `LOWEST_PRECEDENCE` |

> `LoggingFilter`는 체인 진입 시 요청을 로그로 남기고, `chain.filter(exchange).then(...)` 으로 응답 완료 후 상태 코드와 소요 시간을 기록한다.

---

## ① RequestContextFilter

```
X-Trace-Id 헤더 존재?
  Yes → traceId = 헤더 값
  No  → traceId = UUID.randomUUID()

requestedAt = Instant.now()

exchange.attributes["com.example.gw.gateway.RequestContext"] = RequestContext { traceId, requestedAt }
```

이후 모든 필터와 `GatewayRoutingFilter`는 `exchange.getAttribute(RequestContext.ATTR_KEY)` 로 꺼내 사용한다.

---

## ② Route 매칭

`StandaloneRouteDefinitionLocator`가 `router.json`을 읽어 SCG RouteDefinition으로 변환한다.  
매칭된 Route의 metadata에 라우팅 분기에 필요한 값이 담긴다.

```
Route {
  uri      : "grpc://host:port"
  metadata : {
    destinationKind : "Flow"
    flowId          : "test-flow-id"
  }
}
```

---

## ③ Policy GatewayFilter 체인

라우트에 정책이 설정된 경우에만 실행된다. 인증/인가 실패 시 체인을 종료하고 HTTP 4xx를 반환한다.

| 필터 | 동작 |
|---|---|
| `JwtValidationGatewayFilter` | Authorization 헤더의 JWT를 검증한다 |
| `IpFilterGatewayFilter` | 요청 IP가 허용 목록에 있는지 확인한다 |
| `ApiKeyAuthGatewayFilter` | API Key 헤더를 검증한다 |

---

## ④ GatewayRoutingFilter — Flow 라우팅 (ADR-0008)

`destinationKind == "Flow"` 일 때 비동기 StartFlow / SendResponse 패턴으로 처리한다.

```
HTTP Client              GatewayRoutingFilter          Flow Engine (CoreRuntimeService)
     │                          │                                │
     │                    [Step 1]                               │
     │                    PendingResponseRegistry                │
     │                    .register(guid, Sinks.One)             │
     │                    ← StartFlow 전에 먼저 등록             │
     │                          │                                │
     │                    [Step 2]                               │
     │                          │── StartFlow(envelope) ────────▶│
     │                          │     guid        = traceId      │
     │                          │     flowId      = test-flow-id │
     │                          │     payload     = HTTP body     │
     │                          │     contentType = Content-Type  │
     │                          │     startedAt   = requestedAt   │
     │                          │     action      = START_REQUEST │
     │                          │                                │
     │                          │◀─ GatewayCoreAck{RECEIVED} ───│
     │                          │   (즉시 반환 — 실행 완료 아님) │
     │                          │                                │
     │                    [Step 3]                               │
     │                    responseSink.asMono() 대기             │
     │                    (HTTP 응답 보류)                        │
     │                          │                                │
     │            GatewayRuntimeService                          │
     │            (port: listener-grpc.json)                     │
     │                          │                                │
     │                    [Step 4]                               │
     │                          │◀─ SendResponse(envelope) ─────│
     │                          │     guid        = traceId      │
     │                          │     payload     = 응답 body    │
     │                          │     contentType = Content-Type  │
     │                          │     status      = SUCCESS       │
     │                          │                                │
     │                    PendingResponseRegistry                │
     │                    .complete(guid) → sink 방출            │
     │                          │── GatewayCoreAck{RECEIVED} ──▶│
     │                          │                                │
     │                    [Step 5] HTTP 응답 작성               │
     │                    payload     → response body            │
     │                    contentType → Content-Type 헤더       │
     │                    SUCCESS     → HTTP 200                │
     │◀─────────────────────────│                                │
     │  HTTP 200                │                                │
     │  {"result":"ok"}         │  doOnTerminate()               │
     │                          │                                │
     │                    [Step 6] fire-and-forget               │
     │                    Schedulers.boundedElastic()            │
     │                          │── ReportResponseResult(envelope)▶│
     │                          │◀─ GatewayCoreAck{RECEIVED} ───│
```

### Step 1: race condition 방지

`StartFlow`를 호출하기 전에 sink를 등록한다.  
Flow 엔진이 `StartFlow` ACK보다 `SendResponse`를 먼저 보내더라도 sink가 이미 존재하므로 유실되지 않는다.

### Step 6: fire-and-forget

`ReportResponseResult`는 `doOnTerminate()`로 HTTP 응답 전송 직후 트리거된다.  
HTTP 연결이 닫혀도 `boundedElastic` 스레드에서 독립적으로 실행되며, 실패 시 로그만 남기고 예외를 전파하지 않는다.

---

## 오류 경로

### StartFlow가 ERROR ACK를 반환한 경우

```
StartFlow → GatewayCoreAck{ERROR}
    │
    ├─ pendingResponseRegistry.error(guid, ex)   (등록된 sink 정리)
    └─ HTTP 502 Bad Gateway
       (ReportResponseResult 호출 없음)
```

### SendResponse가 ERROR 상태인 경우

```
SendResponse → GatewayCoreEnvelope{ERROR, errorMessage}
    │
    ├─ HTTP 500 Internal Server Error
    ├─ response body = errorMessage
    └─ doOnTerminate → ReportResponseResult 호출됨
```

---

## gRPC 서버 포트 설정

게이트웨이는 `listener-*.json` 설정 파일에서 포트를 읽는다.  
`ListenerResource.Protocol` 으로 HTTP 서버와 gRPC 서버를 동일한 config 체계로 관리한다.

```
listener-http.json                   listener-grpc.json
┌──────────────────┐                 ┌──────────────────┐
│ protocol: HTTP   │                 │ protocol: GRPC   │
│ port: 8080       │                 │ port: 19998      │
└────────┬─────────┘                 └────────┬─────────┘
         │                                    │
         ▼                                    ▼
ListenerPortCustomizer           GatewayGrpcServerConfig
WebServer 포트 설정               GatewayRuntimeServiceImpl 기동
                                  (GRPC Listener 없으면 미기동)
```

---

## 관련 문서

- [ADR-0006](adr/0006-unified-gateway-routing-filter.md) — 단일 GatewayRoutingFilter로 HTTP / gRPC 라우팅 통합
- [ADR-0008](adr/0008-gateway-flow-async-request-response.md) — StartFlow / SendResponse 비동기 분리
- [grpc-use-case.md](grpc-use-case.md) — Flow 엔진과의 gRPC 통신 시나리오

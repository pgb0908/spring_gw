# 요청 처리 흐름

Spring Cloud Gateway가 HTTP 요청을 수신해 Flow 엔진으로 전달하고, 응답을 반환하기까지의 전체 흐름을 설명한다.  
Gateway ↔ Core 간 통신은 모두 HTTP이다 (gRPC 제거, ADR-0008 참조).

---

## 전체 구조

```
                        ┌─────────────────────────────────────────────────────┐
                        │         IIP Gateway (Standalone Mode)               │
                        │                                                     │
 외부 HTTP 클라이언트   │  ┌──────────────────────────────────────┐           │
 ──────────────────────▶│  │  INGRESS Listener (Spring Netty)     │           │
                        │  │  port: ListenerResource role=INGRESS │           │
                        │  └──────────────────────────────────────┘           │
                        │                                                     │
 Flow 엔진 (Core)       │  ┌──────────────────────────────────────┐           │
 ──────────────────────▶│  │  EGRESS Listener (Reactor Netty)     │           │
 외부 Connector 백엔드  │  │  port: ListenerResource role=EGRESS  │           │
 ◀──────────────────────│  └──────────────────────────────────────┘           │
                        └─────────────────────────────────────────────────────┘
```

---

## 필터 실행 순서 (INGRESS)

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

## Ingress Flow — 외부 클라이언트 → GW → Flow 엔진

```
┌───────────────────────────────────────────────────────────────────────────────┐
│  외부 HTTP 클라이언트                                                           │
└────────────────────────────────┬──────────────────────────────────────────────┘
                                 │  POST /api/orders  (HTTP)
                                 ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│  INGRESS Listener — Spring Netty                                               │
│                                                                               │
│  [1] RequestContextFilter                                                     │
│      · X-Trace-Id 헤더 존재 → traceId = 헤더 값                               │
│      · X-Trace-Id 헤더 없음 → traceId = UUID.randomUUID()                     │
│      · requestedAt = Instant.now()                                            │
│      · exchange.attributes["RequestContext"] 에 저장                           │
│                                                                               │
│  [2] Policy GatewayFilter 체인  (라우트에 정책이 설정된 경우)                  │
│      · IpFilterGatewayFilter      CIDR 화이트리스트 검사 → 실패 시 403         │
│      · JwtValidationGatewayFilter JWT 서명·만료 검증    → 실패 시 401         │
│      · ApiKeyAuthGatewayFilter    API Key 헤더 검증     → 실패 시 401         │
│                                                                               │
│  [3] GatewayRoutingFilter  (destinationKind == "Flow")                        │
│      · setAlreadyRouted() — SCG 기본 라우팅 차단                               │
│      · guid = RequestContext.traceId                                          │
│      · FlowEnvelope 생성                                                       │
│          status      = "RECEIVED"                                             │
│          action      = "START_REQUEST"                                        │
│          flowId      = Route.metadata.flowId                                  │
│          payload     = Base64(requestBody)                                    │
│          contentType = Content-Type 헤더                                       │
│          startedAt   = requestedAt (ms)                                       │
│                                                                               │
│      ① PendingResponseRegistry.register(guid, Sinks.One)  ← 先등록            │
│         (StartFlow 전에 sink를 등록해야 race condition 방지)                   │
│                                                                               │
│      ② CoreHttpClient.postStartFlow(flowId, envelope)                         │
│           POST http://{flowHost}:{flowPort}/core/flows/start ──────────────┐  │
│           ◀── { status: "RUNNING" } ACK  (즉시 반환)                        │  │
│                                                                             │  │
│      ③ responseSink.asMono()  ···· 대기 ····                               │  │
│                                                                             │  │
└─────────────────────────────────────────────────────────────────────────────┼──┘
                                                                              │
                        Flow 엔진이 처리 완료 후 역호출                        │
                                                                              │
                                   ┌──────────────────────────────────────────┘
                                   │  POST /gateway/ingress/response
                                   ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│  EGRESS Listener — Reactor Netty                                               │
│                                                                               │
│  [4] IngressResponseHandler                                                   │
│      · FlowEnvelope 파싱  (action = "RESPONSE_REQUEST")                       │
│      · guid 유효성 검사                                                        │
│      · PendingResponseRegistry.complete(guid, envelope)  → Sinks.One 방출    │
│      ◀── HTTP 200 { status: "RUNNING" } ACK  (즉시 반환)                     │
└───────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │  sink emit
                                   ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│  GatewayRoutingFilter (계속)                                                  │
│                                                                               │
│  [5] writeHttpResponse()                                                      │
│      · envelope.status == "ERROR"   → HTTP 500 + errorMessage body           │
│      · envelope.status == "RUNNING" → HTTP 200 + Base64.decode(payload) body │
│                                        Content-Type ← envelope.contentType   │
│                                                                               │
│  [6] doOnTerminate: CoreHttpClient.postResponseAckAsync()  [fire-and-forget]  │
│      POST http://{flowHost}:{flowPort}/core/flows/response-ack                │
│      envelope.action = "RESPONSE_ACK"                                        │
└─────────────────────────────────────────────────────────────────────────────┬─┘
                                                                              │
                                                                              ▼
                                                              외부 HTTP 클라이언트
                                                              HTTP 200 / 500
```

### API 명세 — GW ↔ Core (Ingress)

| 방향 | 메서드 | 경로 | 주요 필드 |
|---|---|---|---|
| GW → Core | POST | `/core/flows/start` | action=`START_REQUEST`, status=`RECEIVED`, payload=Base64 |
| Core → GW | POST | `/gateway/ingress/response` | action=`RESPONSE_REQUEST`, status=`RUNNING`, payload=Base64 |
| GW → Core | POST | `/core/flows/response-ack` | action=`RESPONSE_ACK`, status=`RUNNING` |

### 오류 경로

```
postStartFlow → { status: "ERROR" }
    ├─ pendingResponseRegistry.error(guid, ex)   (등록된 sink 정리)
    └─ HTTP 502 Bad Gateway  (postResponseAckAsync 호출 없음)

IngressResponseHandler → guid 없거나 파싱 실패
    └─ HTTP 400 Bad Request

responseSink → envelope.status == "ERROR"
    ├─ HTTP 500 Internal Server Error
    ├─ body = envelope.errorMessage
    └─ doOnTerminate → postResponseAckAsync 호출됨
```

---

## Egress Flow — Flow 엔진 → GW → 외부 Connector 백엔드

```
┌───────────────────────────────────────────────────────────────────────────────┐
│  Flow 엔진 (Core)                                                              │
└────────────────────────────────┬──────────────────────────────────────────────┘
                                 │  POST /gateway/connector/request
                                 │  FlowEnvelope { action: "CONNECTOR_REQUEST",
                                 │                 payload: Base64(body),
                                 │                 header: { ... } }
                                 ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│  EGRESS Listener — Reactor Netty                                               │
│                                                                               │
│  [1] EgressConnectorHandler                                                   │
│      · FlowEnvelope 파싱                                                       │
│      ◀── HTTP 200 { status: "RUNNING" } ACK  (즉시 반환)                     │
│      · doOnTerminate → processAsync()  [Schedulers.boundedElastic]            │
│                                                                               │
│  [2] processAsync()                                                           │
│      · resolveBackendUrl(connectorId)                                         │
│          ConnectorResource.loadBalancing.targets[0] → http://{host}:{port}   │
│      · enrichWithGatewayId(req)                                               │
│          gateway.metadata.name → envelope.gatewayId                          │
│                                                                               │
│  [3] ConnectorCallExecutor.execute(backendUrl, envelope)                      │
│      · Base64.decode(payload) → HTTP 요청 본문                                 │
│      · envelope.header         → HTTP 요청 헤더 그대로 전달                    │
│      · POST {backendUrl} ────────────────────────────────────────────────┐   │
│                                                                          │   │
└──────────────────────────────────────────────────────────────────────────┼───┘
                                                                           │
                                    외부 Connector 백엔드 응답              │
                                    ◀──────────────────────────────────────┘
                                           │
                                           ▼
                              ConnectorCallExecutor.buildResponse()
                              · HTTP 2xx  → status="RUNNING"
                                            payload=Base64(responseBody)
                                            header=responseHeaders
                              · HTTP 4xx/5xx → status="ERROR"
                                               errorCode="BACKEND_ERROR"
                                           │
                                           ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│  [4] CoreCallbackClient.postResponse(callbackEnvelope)                        │
│      POST http://{coreHost}:{corePort}/gateway/connector/response             │
│      (coreId → baseUrl 맵에서 조회)                                            │
└─────────────────────────────────────────────────────────────────────────────┬─┘
                                                                              │
                                                                              ▼
                                                              Flow 엔진 (Core)
                                                              FlowEnvelope { action: "CONNECTOR_RESPONSE" }
```

### API 명세 — GW ↔ Core (Egress)

| 방향 | 메서드 | 경로 | 주요 필드 |
|---|---|---|---|
| Core → GW | POST | `/gateway/connector/request` | action=`CONNECTOR_REQUEST`, payload=Base64 |
| GW → Core | POST | `/gateway/connector/response` | action=`CONNECTOR_RESPONSE`, status=`RUNNING`/`ERROR`, payload=Base64 |

---

## FlowEnvelope — 공통 DTO

Gateway ↔ Core 간 모든 HTTP 요청·응답에 사용되는 단일 DTO이다.

```
FlowEnvelope {
    guid             string    요청 추적 ID (= RequestContext.traceId)
    status           string    RECEIVED | RUNNING | ERROR
    error_code       string?
    error_message    string?
    flow_id          string
    flow_version     int
    gateway_id       string    이 Gateway의 이름
    core_id          string?   응답을 보낼 Core 식별자
    started_at       long      요청 진입 시각 (ms epoch)
    finished_at      long?
    timeout          long
    ingress_gateway_id string  최초 수신 Gateway 이름
    connector_id     string?   Egress 흐름에서 사용
    node_id          string?
    node_type        string?
    action           string    START_REQUEST | RESPONSE_REQUEST | RESPONSE_ACK
                               CONNECTOR_REQUEST | CONNECTOR_RESPONSE
    payload          string?   Base64 인코딩된 HTTP 본문
    charset          string?
    content_type     string?
    header           Map?      HTTP 헤더 맵 (Egress 흐름)
}
```

---

## 부팅 시 설정 로딩 (Standalone Mode)

```
StandaloneConfigLoader.getConfig()
        │  JSON 파일 스캔 (kind 필드로 분류)
        │  → LoadedConfig { listeners, gateway, routers, connectors, flows, policies }
        │
        ├── ListenerPortCustomizer            INGRESS Listener port → Spring Netty 포트 설정
        ├── EgressListenerServerConfig        EGRESS Listener → Reactor Netty 서버 기동
        ├── StandaloneRouteDefinitionLocator  RouteTranslator → SCG RouteDefinition
        ├── StandaloneGlobalPolicyConfig      Gateway.globalPolicy → CorsWebFilter
        ├── StandalonePolicyRegistrar         LoadedConfig.policies → PolicyRegistry 등록
        └── StandaloneCoreClientConfig
                ├── CoreHttpClient            flowId  → http://host:port  맵 구성
                └── CoreCallbackClient        coreId  → http://host:port  맵 구성
```

---

## 관련 문서

- [ADR-0006](adr/0006-unified-gateway-routing-filter.md) — 단일 GatewayRoutingFilter로 라우팅 통합
- [ADR-0008](adr/0008-gateway-flow-async-request-response.md) — StartFlow / 역호출 비동기 분리 (HTTP 기반)

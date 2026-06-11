# 단일 GatewayRoutingFilter로 HTTP와 gRPC 라우팅 통합

Connector(HTTP) 라우팅은 `NettyRoutingFilter`, Flow(gRPC) 라우팅은 `FlowGatewayFilterFactory`로 분산되어 있던 구조를 단일 `GatewayRoutingFilter`로 통합한다.

## Considered Options

- **기존 구조 유지** (`NettyRoutingFilter` + `FlowGatewayFilterFactory`): Flow 라우트는 `h2c://` URI를 가지면서 동시에 `FlowGatewayFilterFactory`가 체인을 종료한다. URI가 실제 동작을 반영하지 않고, 라우팅 로직이 두 곳에 분산된다.
- **단일 GatewayRoutingFilter** ← 선택: SCG의 `NettyRoutingFilter`를 auto-config에서 제외하고, `GatewayRoutingFilter`(GlobalFilter, terminal)가 모든 라우팅을 담당한다. Route metadata의 `destinationKind`로 분기하여 Connector는 Netty HttpClient에 위임하고, Flow는 `GatewayCoreService.ExecuteFlow` gRPC를 호출한다. `FlowGatewayFilterFactory`는 제거.

## 설계

`RouteTranslator`가 Flow 라우트를 번역할 때 RouteDefinition에 metadata를 추가한다:

```
RouteDefinition {
  uri: "grpc://host:port"
  metadata: { destinationKind: "Flow", flowId: "flow-xyz" }
}
```

`GatewayRoutingFilter`는 실행 시점에 `GATEWAY_ROUTE_ATTR`로 매칭된 Route를 읽고:
- `destinationKind == "Flow"` → `GatewayCoreService.ExecuteFlow` 호출
- `destinationKind == "Connector"` (또는 metadata 없음) → Netty HttpClient 위임

## Consequences

라우팅 로직이 단일 필터에 집중되어 Connector와 Flow가 대칭 구조를 가진다. Flow 라우트의 URI scheme(`grpc://`)이 실제 전송 프로토콜을 반영한다. `FlowGatewayFilterFactory`가 제거되어 per-route 필터 체인이 Policy 필터만 포함한다. 단, `NettyRoutingFilter`가 제공하던 HTTP 스트리밍·웹소켓 등의 기능은 `GatewayRoutingFilter`에서 직접 구현해야 한다.

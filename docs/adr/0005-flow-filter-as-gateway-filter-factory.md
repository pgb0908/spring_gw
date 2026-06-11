# FlowGatewayFilter를 GatewayFilterFactory로 구현

Flow 목적지로의 HTTP→gRPC 변환 필터를 `GlobalFilter`가 아닌 per-route `GatewayFilterFactory`로 구현한다.

## Considered Options

- **GlobalFilter** (초기 구현): 모든 요청에 개입하여 `X-Flow-Id` 헤더 존재 여부로 Flow 요청을 판별한다. 구현이 단순하지만, `X-Flow-Id`는 `AddRequestHeader` 라우트 필터가 주입하는데 GlobalFilter가 그보다 먼저 실행되어 헤더를 감지하지 못하는 순서 버그가 발생한다. Flow가 아닌 모든 요청에도 불필요한 헤더 조회가 발생한다.
- **GatewayFilterFactory** ← 선택: `RouteTranslator`가 Flow 목적지 라우트에만 명시적으로 이 필터를 추가한다. 실행 순서가 라우트 필터 체인 내에서 결정되므로 순서 버그가 없고, "이 라우트는 gRPC로 처리한다"는 의도가 RouteDefinition에 직접 표현된다.

## Consequences

`RouteTranslator`가 Flow 목적지를 번역할 때 `FlowGatewayFilterFactory`가 생성한 `GatewayFilter`를 RouteDefinition의 필터 목록에 추가해야 한다. Policy 필터(Security order:5, Traffic order:10 등)와 같은 필터 체인에 속하므로, FlowGatewayFilter의 order는 모든 Policy 필터보다 높은 값(마지막 실행)으로 설정해야 한다.

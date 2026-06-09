# 단일 범용 GatewayCoreService 사용

Flow 엔진과의 gRPC 통신을 Flow별 개별 서비스가 아닌 단일 `GatewayCoreService.ExecuteFlow` RPC로 처리한다. 게이트웨이의 역할은 라우팅이지 비즈니스 메시지 타입을 아는 것이 아니므로, `flow_id`와 raw payload만 전달하고 해석은 Flow 엔진에 위임한다. Flow가 추가될 때마다 게이트웨이 proto와 코드를 변경해야 하는 결합을 피하기 위해 이 방식을 선택했다.

## Considered Options

- **Flow별 개별 서비스** (`OrderFlowService`, `PaymentFlowService` 등): 타입 안전성은 높지만 Flow 추가마다 게이트웨이 변경 필요. 게이트웨이와 비즈니스 도메인이 결합된다.
- **단일 범용 서비스** ← 선택: `flow_id`로 서버가 분기. 게이트웨이는 Flow의 비즈니스 의미를 알지 못한 채 라우팅만 담당한다.

## Consequences

Flow 엔진 내부 변경(새 Flow 추가, 메시지 구조 변경)이 게이트웨이에 영향을 주지 않는다. 단, 게이트웨이 수준에서 요청/응답 페이로드의 타입 검증은 불가능하다.

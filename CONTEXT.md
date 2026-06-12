# IIP Gateway

API 트래픽을 백엔드 서비스로 라우팅하는 게이트웨이. 로컬 config 파일로 독립 실행하거나(Standalone), 마스터 서버에서 config를 수신해 실행한다(Online).

## Language

**Listener**:
게이트웨이가 인바운드 트래픽을 수신하는 포트/프로토콜 바인딩.
_Avoid_: Port, endpoint, server

**Gateway**:
전역 정책(CORS, IP 필터, Rate Limit)과 관측성 설정의 묶음. 라우트 매칭 이전에 모든 요청에 적용된다.
_Avoid_: Config, settings, global config

**Router**:
요청 매칭 규칙(path, method)과 목적지(Connector 또는 Flow)를 연결하는 규칙.
_Avoid_: Route, rule, mapping

**Connector**:
HTTP/HTTPS 백엔드 서버 집합과 연결 정책(로드밸런싱, 재시도, 서킷 브레이커).
_Avoid_: Backend, upstream, service, target

**Flow**:
Integration 엔진과의 gRPC 통신 정의. Connector와 유사하나 각 타겟에 `flow-id`를 포함한다.
_Avoid_: gRPC service, integration connector

**Standalone Mode**:
로컬 파일 시스템의 JSON 리소스 파일들을 읽어 게이트웨이를 구성하는 부팅 방식.
_Avoid_: Local mode, file mode, offline mode

**Online Mode**:
마스터 서버와 통신하여 config를 동적으로 수신한 뒤 게이트웨이를 구성하는 부팅 방식.
_Avoid_: Remote mode, server mode, dynamic mode

## Relationships

- 하나의 **Gateway**는 하나 이상의 **Listener**를 가진다
- **Router**는 하나 이상의 **Connector** 또는 **Flow**를 목적지로 참조한다
- **Connector**와 **Flow**는 독립적으로 정의되며 여러 **Router**에서 재사용 가능하다
- **Gateway** 글로벌 정책은 **Router** 매칭 이전에 실행된다

## Example dialogue

> **Dev:** "새 백엔드 서버를 추가하려면 어디를 바꿔야 해?"
> **Domain expert:** "**Connector**에 target을 추가하면 돼. **Router**는 건드릴 필요 없어 — **Connector** 이름으로 참조하고 있으니까."

> **Dev:** "Integration 엔진으로 보내는 요청은 **Connector**로 처리해?"
> **Domain expert:** "아니, 그건 **Flow**야. gRPC로 통신하고 `flow-id`가 필요해서 별도 리소스로 분리돼 있어."

## Flow 통신 계약

**GatewayCoreService**:
게이트웨이와 Flow 엔진 사이의 gRPC 서비스 계약. 단일 RPC `ExecuteFlow`로 모든 Flow 호출을 처리한다.
_Avoid_: FlowService, IntegrationService

**RuntimeHeader**:
모든 Flow 호출에 첨부되는 요청 추적 메타데이터. `request_id`, `trace_id`, HTTP 헤더에서 전파된 `attributes`를 포함한다.
_Avoid_: Header, GrpcMetadata, RequestContext

**RuntimePayload**:
HTTP body를 gRPC 경계 너머로 전달하는 래퍼. `body`(raw bytes)와 `content_type`을 함께 전달해 Flow 엔진이 스스로 파싱한다.
_Avoid_: Payload, Body, Message

**RuntimeError**:
Flow 엔진이 반환하는 구조화된 오류. `code`, `message`, `detail`을 포함한다.
_Avoid_: Error, GrpcError, FlowError

## Policy 실행 계약

**Policy**:
Router에 부착되는 per-route 필터 설정 리소스. `spec.targetRef`로 Router를 참조하고, `spec.order` 값에 따라 오름차순으로 실행된다. top-level `type` 필드는 적용할 필터의 이름이며, 이 값이 곧 Spring Cloud Gateway의 `GatewayFilterFactory` bean 이름으로 사용된다.
_Avoid_: Filter config, middleware, plugin

**Policy type**:
Policy 리소스의 `type` 필드 값. `IpFilter`, `JwtValidation` 등 개별 필터 이름을 직접 지정한다. 카테고리(Security/Traffic 등) 개념은 없으며, 실행 순서는 `spec.order`만으로 제어한다.
_Avoid_: policyKind, subType, category, Security/Traffic/Enhance/Transform

**Policy 부착 방향**:
Policy가 Router를 참조한다 (`Policy.spec.targetRef → Router`). Router는 자신에게 붙은 Policy를 알지 못한다.
_Avoid_: Router가 Policy를 소유한다, Router inline policy

**PolicyRegistry**:
Policy 이름 → `PolicyResource` 조회를 제공하는 런타임 저장소. `StandaloneConfigLoader`가 부팅 시 등록하고, Online Mode에서도 동일한 인터페이스로 등록한다. `GatewayFilterFactory` 구현체들이 이를 주입받아 policy 이름으로 설정을 조회한다.
_Avoid_: PolicyStore, PolicyMap, LoadedConfig 직접 주입

## Flagged ambiguities

- "route"는 **Router** 리소스 자체와 Spring Cloud Gateway 내부의 RouteDefinition 양쪽으로 사용될 수 있음 — 도메인 용어로는 **Router**를 사용한다.

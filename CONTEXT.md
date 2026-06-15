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
Integration 엔진(Core)과의 HTTP 통신 정의. Connector와 유사하나 각 타겟에 `flow-id`와 `core-id`를 포함한다. StartFlow / ResponseRequest / ResponseAck 3단계 비동기 패턴으로 동작한다.
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
> **Domain expert:** "아니, 그건 **Flow**야. HTTP로 통신하고 `flow-id`가 필요해서 별도 리소스로 분리돼 있어."

## Flow 통신 계약

**FlowEnvelope**:
게이트웨이와 Flow 엔진(Core) 사이에 주고받는 모든 HTTP 메시지의 공통 JSON 포맷. Ingress(StartFlow, ResponseRequest, ResponseAck)와 Egress(ConnectorRequest, ConnectorResponse) 양방향에 동일한 구조를 사용한다. `guid`, `flow_id`, `core_id`, `gateway_id`, `action`, `status`, `payload`(base64), `content_type`, `header` 맵 등을 포함한다.
_Avoid_: GatewayCoreEnvelope, FlowMessage, CoreEnvelope

**StartFlow**:
GW가 Flow 엔진에 플로우 실행을 요청하는 메시지. `POST /core/flows/start`. `action: START_REQUEST`, `status: RECEIVED`. GW가 원본 HTTP 요청을 수신한 시점의 payload와 content_type을 포함한다. Core는 즉시 `status: RUNNING` ACK를 반환한다.
_Avoid_: FlowStart, TriggerFlow, ExecuteFlow

**ResponseRequest**:
Flow 엔진이 플로우 실행 결과를 GW로 전달하는 콜백. `POST /gateway/ingress/response`. `action: RESPONSE_REQUEST`. payload는 HTTP 클라이언트에게 돌려줄 응답 본문(base64). GW는 즉시 ACK를 반환하고 HTTP 클라이언트에게 응답을 전송한다.
_Avoid_: SendResponse, FlowResponse, CoreCallback

**ResponseAck**:
GW가 HTTP 클라이언트 응답 전송 완료를 Core에 보고하는 fire-and-forget 메시지. `POST /core/flows/response-ack`. `action: RESPONSE_ACK`. 실패해도 HTTP 클라이언트는 이미 응답을 받은 상태이므로 로그로만 기록한다.
_Avoid_: ReportResponseResult, ResponseConfirm, FlowAck

**CoreHttpClient**:
GW에서 Flow 엔진(Core)으로의 HTTP 호출을 캡슐화하는 컴포넌트. `flow_id` → Core 베이스 URL(`http://{host}:{port}`) 매핑을 관리하며, `postStartFlow`, `postResponseAck` 메서드를 제공한다. `FlowResource.Target`에서 매핑을 로드한다.
_Avoid_: FlowClient, CoreGrpcClient, FlowStub

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

## 요청 컨텍스트

**RequestContext**:
게이트웨이에 요청이 진입하는 순간 생성되어 `ServerWebExchange.getAttributes()`에 저장되는 요청 범위 객체. `traceId`와 `requestedAt`을 담으며, 이후 모든 필터와 라우팅 단계에서 참조한다.
_Avoid_: RequestInfo, GatewayContext, ExchangeContext

**traceId**:
요청마다 유일한 식별자. `X-Trace-Id` 헤더가 있으면 그 값을 사용하고, 없으면 게이트웨이가 UUID를 생성한다. `RequestContext`에 저장되며 `RuntimeHeader.trace_id`로 Flow 엔진에 전파된다.
_Avoid_: guid, requestGuid, correlationId

**requestedAt**:
게이트웨이가 요청을 수신한 시각(`Instant`). `RequestContext`에 저장되며 응답 지연 측정 등에 사용된다.
_Avoid_: startTime, receivedAt, timestamp

## Egress 통신 계약

**Egress Flow**:
Flow 엔진이 외부 서비스 호출을 GW에 위임하는 패턴. GW가 Connector를 통해 외부 백엔드를 호출하고 결과를 Flow에 돌려준다. Ingress Flow(HTTP 클라이언트 → GW → Flow)와 방향이 반대다.
_Avoid_: Outbound flow, reverse flow, callback flow

**ConnectorEnvelope**:
Egress Flow에서 사용하는 메시지 포맷의 별칭. 실체는 **FlowEnvelope**와 동일한 구조다. CONNECTOR_REQUEST와 CONNECTOR_RESPONSE 양쪽에 사용된다.
_Avoid_: EgressMessage, ConnectorMessage

**CONNECTOR_REQUEST**:
Flow 엔진이 GW에 외부 Connector 호출을 요청하는 메시지. `action: CONNECTOR_REQUEST`. `payload`는 base64 인코딩된 외부 백엔드 HTTP 요청 본문이며, `header` 맵은 외부 백엔드로 전달할 HTTP 헤더다. GW는 즉시 `status: RUNNING` ACK를 반환하고 비동기로 처리한다.
_Avoid_: ConnectorCallRequest, FlowRequest, EgressRequest

**CONNECTOR_RESPONSE**:
GW가 외부 Connector 호출 결과를 Flow 엔진에 전달하는 콜백 메시지. `action: CONNECTOR_RESPONSE`. 외부 백엔드 응답 본문이 `payload`(base64)로, 응답 헤더가 `header` 맵으로 담긴다. 성공 시 `status: RUNNING`(Flow 실행 계속), 실패 시 `status: ERROR`와 `error_code`를 포함한다.
_Avoid_: ConnectorCallResponse, FlowResponse, EgressResponse

**Egress Listener**:
Flow 엔진이 GW에 내부 메시지를 보내는 전용 Listener 포트. CONNECTOR_REQUEST(Egress)와 ResponseRequest(Ingress 콜백) 양쪽을 모두 수신한다. 외부 클라이언트 트래픽을 수신하는 Ingress Listener와 분리된 포트에서 동작한다.
_Avoid_: Internal listener, Flow listener, callback port

## Flagged ambiguities

- "route"는 **Router** 리소스 자체와 Spring Cloud Gateway 내부의 RouteDefinition 양쪽으로 사용될 수 있음 — 도메인 용어로는 **Router**를 사용한다.
- CONNECTOR_RESPONSE의 `status: RUNNING`은 커넥터 호출 성공을 뜻하지 않는다 — Flow 실행이 아직 진행 중임을 나타내는 Flow 실행 상태값이다.

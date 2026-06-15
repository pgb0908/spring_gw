# Egress Connector 호출 비동기 HTTP 패턴 (CONNECTOR_REQUEST / CONNECTOR_RESPONSE)

Flow 엔진이 외부 서비스 호출을 GW에 위임할 때 HTTP 기반 비동기 요청-콜백 패턴을 채택한다. Flow는 `CONNECTOR_REQUEST`를 GW에 POST하고 즉시 `RUNNING` ACK를 받으며, GW가 외부 백엔드 호출을 완료한 뒤 `CONNECTOR_RESPONSE`를 Flow에 별도 POST로 콜백한다.

## Considered Options

- **CONNECTOR_REQUEST 동기 응답**: GW가 외부 백엔드 호출을 완료한 뒤 그 결과를 CONNECTOR_REQUEST의 HTTP 응답 본문으로 직접 반환. 구현이 단순하지만, 외부 백엔드 응답 시간이 길어지면 Flow 엔진의 커넥션을 장시간 점유하고 타임아웃 관리가 복잡해진다.
- **CONNECTOR_REQUEST + CONNECTOR_RESPONSE 비동기 분리** ← 선택: GW는 수신 확인(`RUNNING` ACK)만 즉시 반환하고, 외부 백엔드 호출 완료 후 Flow 엔진의 `/gateway/connector/response`를 별도 POST로 콜백한다. ADR 0008의 Ingress 비동기 패턴(StartFlow / SendResponse)과 대칭적인 구조다.

## 설계

```
Flow 엔진
  → POST /gateway/connector/request (ConnectorEnvelope, action=CONNECTOR_REQUEST)
    Egress Listener (전용 포트, Ingress Listener와 분리)
      → SCG Router 매칭
        → Egress ConnectorFilter
            1. ConnectorEnvelope JSON 파싱 (guid, core_id, payload base64, header 맵 추출)
            2. 즉시 ACK 반환: {guid, status: RUNNING}
            3. payload base64 디코딩 → HTTP 요청 본문
               header 맵 → HTTP 요청 헤더
               → 외부 백엔드 HTTP 호출 (비동기)

외부 백엔드 응답 수신 후
  → POST http://{core_id_host}:{core_id_port}/gateway/connector/response
     ConnectorEnvelope {
       guid, flow_id, core_id, node_id, node_type  ← 요청에서 에코
       gateway_id                                   ← GW가 채움
       connector_id                                 ← GW가 채움 (현재는 미구현)
       finished_at                                  ← GW가 채움
       action = CONNECTOR_RESPONSE
       payload = 외부 백엔드 응답 본문 (base64)
       header  = 외부 백엔드 응답 헤더 맵
       status  = RUNNING (성공) | ERROR (실패)
       error_code, error_message (실패 시)
     }
  ← Flow 엔진: {guid, status: RUNNING} ACK
```

**core_id → Flow 콜백 URL**: `FlowResource.Target`에 `core-id` 필드를 추가해 관리한다. 콜백 URL은 `http://{target.host}:{target.port}/gateway/connector/response`로 고정이며, 동일한 Target의 `host:port`가 gRPC(Ingress) 통신과 HTTP 콜백(Egress) 양쪽에 재사용된다.

**guid**: CONNECTOR_REQUEST마다 Flow 엔진이 생성하는 독립 UUID. Ingress 요청의 `traceId`와 무관하다.

**헤더 처리**: CONNECTOR_REQUEST의 `header` 맵은 외부 백엔드 HTTP 요청 헤더로 변환된다. 외부 백엔드 응답 헤더는 CONNECTOR_RESPONSE `header` 맵으로 변환되어 Flow에 전달된다.

## Consequences

- GW는 CONNECTOR_REQUEST ACK 반환 직후 커넥션을 닫고 비동기로 백엔드 호출을 수행한다. 백엔드 호출 실패 또는 Flow 콜백 실패 시 Flow 엔진이 응답을 받지 못하므로, 운영 환경에서는 timeout 기반 만료 처리가 필요하다 (현재 미구현).
- `connector_id`는 현재 null로 수신되며 무시한다. 추후 Flow 엔진이 값을 제공하면 해당 Connector 리소스를 라우팅에 활용한다.
- `FlowResource.Target`이 gRPC(Ingress StartFlow)와 HTTP 콜백(Egress CONNECTOR_RESPONSE) 두 가지 통신에 동시에 사용된다. 프로토콜이 다르므로 Target 설정 시 양쪽 포트가 일치하는지 운영 상 주의가 필요하다.
- Egress Listener는 Ingress Listener와 별도 포트로 분리한다. 외부 클라이언트가 `/gateway/connector/request` 엔드포인트에 직접 접근하는 것을 방지하기 위함이다.

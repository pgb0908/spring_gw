# RuntimePayload를 bytes + content_type으로 설계

Flow 엔진으로 전달하는 페이로드를 `google.protobuf.Any`나 구조화된 메시지 대신 `bytes body + string content_type`으로 정의한다. 게이트웨이는 HTTP body의 내용을 해석하지 않고 그대로 바이트로 전달하며, `content_type`을 함께 보내 Flow 엔진이 스스로 파싱하도록 한다.

## Considered Options

- **`google.protobuf.Any`**: 타입 정보를 포함할 수 있지만 양쪽이 타입을 알아야 해 결합도가 높아진다.
- **구조화된 메시지**: Flow별로 타입이 명확하지만 ADR-0002와 모순된다 — 단일 서비스이면서 구조화된 메시지를 쓰는 것은 의미가 없다.
- **`bytes + content_type`** ← 선택: 게이트웨이는 HTTP body를 그대로 바이트로 넘기고, UTF-8이 아닌 바이너리 페이로드도 처리 가능하다. Flow 엔진이 `content_type`을 보고 JSON/XML/binary 등을 스스로 파싱한다.

## Consequences

게이트웨이는 페이로드 내용에 무관하게 동작하므로 Flow 엔진의 페이로드 스키마 변경에 영향을 받지 않는다. 단, 게이트웨이 수준에서 페이로드 유효성 검사는 불가능하다.

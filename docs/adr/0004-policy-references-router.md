# Policy가 Router를 참조하는 방향으로 설계

Policy 리소스는 `spec.targetRef`로 Router를 참조한다. Router는 자신에게 부착된 Policy를 알지 못한다.

## Considered Options

- **Router가 Policy 목록을 소유** (`spec.policies: ["orders-security", "orders-traffic"]`): Router 파일 하나만 보면 어떤 정책이 적용되는지 파악할 수 있다. 단, 정책을 추가·제거할 때마다 Router 파일을 수정해야 하고, 여러 Router에 동일 정책을 적용할 때 참조가 아닌 중복이 발생한다.
- **Policy가 Router를 참조** ← 선택: Policy 파일을 독립적으로 추가·제거할 수 있어 Router를 건드리지 않고 정책을 붙이거나 뗄 수 있다. 운영 중 보안 정책을 긴급 교체할 때 Router 변경 없이 Policy 파일만 교체하면 된다.

## Consequences

게이트웨이가 라우트를 구성할 때(`RouteTranslator`) Policy 목록을 역방향으로 조회해야 한다 — 즉, Router 이름을 키로 해당 Router를 타겟으로 하는 Policy들을 찾아 `spec.order` 순서로 필터에 추가한다. Router 파일만 보고 어떤 Policy가 붙어 있는지 알 수 없으므로, 전체 config 디렉터리를 함께 확인해야 한다.

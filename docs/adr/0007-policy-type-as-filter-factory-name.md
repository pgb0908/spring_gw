# ADR-0007: Policy type을 GatewayFilterFactory 이름으로 직접 사용

## Status
Accepted

## Context
Policy 리소스의 `type` 필드가 Security / Traffic / Enhance / Transform 네 카테고리로 정의되어 있었다.
`RouteTranslator`는 switch 문으로 카테고리를 factory 이름에 수동 매핑했고,
각 Factory는 카테고리 안의 모든 필터 로직을 하나의 람다로 인라인 처리했다.

이 구조는 두 가지 문제를 낳았다:
1. 카테고리 단위 Factory 안에 여러 책임이 뭉쳐 유지보수가 어렵다.
2. 새 필터를 추가할 때마다 `RouteTranslator`의 switch를 수정해야 한다.

## Decision
- `Policy.type`을 카테고리가 아닌 **개별 필터 이름**으로 정의한다 (`IpFilter`, `JwtValidation` 등).
- `type` 값이 곧 Spring Cloud Gateway `GatewayFilterFactory` bean 이름이 된다.
- `RouteTranslator`의 switch를 제거하고 `policy.getType()`을 factory 이름으로 직접 사용한다.
- 각 Filter는 책임이 하나인 독립 클래스(`GatewayFilter` 구현체)로 분리한다.
- 실행 순서는 카테고리 기본값(Security=5 등) 없이 `spec.order`만으로 제어한다.
- `PolicyRegistry` 빈을 도입해 Factory들이 policy 이름으로 `PolicyResource`를 조회하게 한다.

## Consequences
- 새 필터 추가 시 `RouteTranslator`를 건드릴 필요가 없다.
- `type` 값이 존재하지 않는 factory 이름이면 런타임까지 감지되지 않는다 — `StandaloneConfigLoader` 검증 단계에서 알려진 type 목록을 체크해야 한다.
- `PolicyRegistry`가 Standalone/Online Mode 양쪽의 공통 인터페이스가 되어 모드 전환에 열려 있다.

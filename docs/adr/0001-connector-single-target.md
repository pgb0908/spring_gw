# Connector 다중 타겟 중 첫 번째만 사용 (뼈대 단계)

Connector 리소스는 여러 타겟과 로드밸런싱 알고리즘을 정의하지만, 뼈대 단계에서는 `targets[0]`만 사용한다. Spring Cloud Gateway에서 정적 타겟 목록에 대한 가중치 기반 로드밸런싱을 구현하려면 커스텀 `ReactorServiceInstanceLoadBalancer`가 필요한데, 이는 라우팅 파이프라인 자체가 검증되기 전에 투자하기엔 비용이 크다. 로드밸런싱은 라우팅 흐름이 안정화된 이후 별도 이슈로 구현한다.

## Considered Options

- **커스텀 `ReactorServiceInstanceLoadBalancer` 구현**: Connector별 타겟 목록을 Spring Cloud LoadBalancer에 동적으로 등록. 정확하지만 뼈대 단계 범위를 초과한다.
- **첫 번째 타겟만 사용** ← 선택. 다중 타겟 설정 시 WARN 로그를 남겨 미구현 사실을 명시한다.

## Consequences

다중 타겟을 가진 Connector는 실제로 단일 타겟처럼 동작한다. 운영 환경 투입 전에 반드시 구현되어야 한다.

# API Gateway Configuration

IIP API 게이트웨이의 설정 리소스를 정의합니다.

모든 리소스는 Kubernetes 스타일의 선언적 모델(`apiVersion`, `kind`, `metadata`, `spec`)을 따르며, `iip.gateway/v1alpha1` 버전을 사용합니다.

## 트래픽 흐름

```
Client → [Listener (인그레스)] → [Router (라우팅 규칙)] → [Service (백엔드)]
                                        │
                           ┌────────────┼────────────┐
                           ▼            ▼            ▼
                     Policy-Security Policy-Traffic Policy-Enhance
                       (order: 5)    (order: 10)   (order: 12)
                                                        │
                                                        ▼
                                                 Policy-Transform
                                                   (order: 15)

Gateway (전역 설정) ─── logging, tracing, metrics ──→ 전체 트래픽에 적용
```

## 리소스 목록

### 전역 리소스

| 파일 | kind | 역할 |
|------|------|------|
| [Gateway.md](Gateway.md) | Gateway | APIM 전역 관측성 설정 (logging, tracing, metrics) |

### 핵심 리소스

| 파일 | kind | 역할 |
|------|------|------|
| [Listener.md](Listener.md) | Listener | 외부 트래픽을 수신할 포트, 프로토콜, TLS 구성 (인그레스) |
| [Router.md](Router.md) | Router | 경로/메서드/헤더 기반 트래픽 매칭과 목적지 라우팅 (카나리아/분할 배포 지원) |
| [Service.md](Service.md) | Service | 백엔드 서버 클러스터, 복원력(retry, circuit breaker), 업스트림 TLS 구성 |

### 정책 리소스

모든 정책은 `kind: Policy`이며, `spec.order` 값에 따라 순차적으로 실행됩니다.

| 파일 | 기본 order | 역할 |
|------|-----------|------|
| [Policy-Security.md](Policy-Security.md) | 5 | JWT 검증, IP 필터링, CORS 제어 |
| [Policy-Traffic.md](Policy-Traffic.md) | 10 | Rate Limit, 동시성 제한, SLA 차등 적용 |
| [Policy-Enhance.md](Policy-Enhance.md) | 12 | 캐싱 (Memory / Redis / Memcached) |
| [Policy-Transform.md](Policy-Transform.md) | 15 | 헤더/쿼리 제어, 바디 변환(XML/JSON), 데이터 마스킹 |

### 기타

| 파일 | 역할 |
|------|------|
| [Mocking-Service.md](Mocking-Service.md) | 백엔드 없이 API 응답을 시뮬레이션 (stub, 지연, 오류율) |

## 리소스 관계

```
Gateway (전역)
  └── logging, tracing, metrics

Listener ←── targetRef ──── Router ←── targetRef ──── Policy
  │                           │
  │                           ├── destinations[].destinationRef → Service
  │                           │
  └── port, protocol, tls     └── rules (path, methods, headers)
      allowedHostnames
      connection              Service
                                └── loadBalancing, healthCheck
                                    retry, circuitBreaker, timeout
                                    upstreamTls, maxBodySize
```

- **Gateway**는 전역 설정으로, 개별 리소스를 참조하지 않고 게이트웨이 전체에 적용됩니다.
- **Router**는 `spec.targetRef`로 Listener를 참조하고, `spec.config.destinations`로 백엔드 Service를 지정합니다.
- **Policy**는 `spec.targetRef`로 Router 또는 Service에 부착됩니다.

## 공통 규칙

### apiVersion

모든 리소스는 `"apiVersion": "iip.gateway/v1alpha1"`을 사용합니다.

### duration 포맷

시간 값은 다음 단위를 지원합니다: `ms` (밀리초), `s` (초), `m` (분), `h` (시간)

```
"10s"    # 10초
"500ms"  # 500밀리초
"5m"     # 5분
"1h"     # 1시간
```

### Policy 실행 순서

`spec.order` 값이 낮을수록 먼저 실행됩니다.

```
Security (5) → Traffic (10) → Enhance (12) → Transform (15)
```

기본값을 그대로 사용하는 것을 권장합니다. 커스텀 order 값을 사용할 경우, 보안 정책이 트래픽 정책보다 반드시 먼저 실행되도록 설정해야 합니다.

---

## 배포 설정 샘플

아래는 ecommerce 서비스를 게이트웨이에 배포하는 전체 설정 예시입니다. 모든 리소스가 어떻게 연결되는지 보여줍니다.

### 1. Gateway — 전역 관측성

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Gateway",
  "metadata": {
    "name": "production-gateway",
    "labels": { "env": "production" }
  },
  "spec": {
    "logging": {
      "accessLog": { "enabled": true, "format": "JSON" }
    },
    "tracing": {
      "enabled": true,
      "samplingRate": 0.1,
      "endpoint": "http://otel-collector.infra:4317"
    },
    "metrics": {
      "enabled": true,
      "path": "/metrics",
      "port": 9090
    }
  }
}
```

### 2. Listener — 인그레스 (HTTPS 443 수신)

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Listener",
  "metadata": {
    "name": "https-listener",
    "labels": { "env": "production" }
  },
  "spec": {
    "protocol": "HTTPS",
    "port": 443,
    "host": "0.0.0.0",
    "allowedHostnames": [
      "api.ecommerce.com",
      "admin.ecommerce.com"
    ],
    "tls": {
      "mode": "TERMINATE",
      "minVersion": "1.3",
      "certificates": [
        {
          "certRef": "/etc/certs/ecommerce.crt",
          "keyRef": "/etc/certs/ecommerce.key"
        }
      ]
    },
    "connection": {
      "readTimeout": "30s",
      "writeTimeout": "30s",
      "maxConnections": 50000
    }
  }
}
```

### 3. Service — 백엔드 (주문 서비스 클러스터)

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Service",
  "metadata": {
    "name": "orders-svc",
    "labels": { "app": "order-service", "env": "production" }
  },
  "spec": {
    "protocol": "HTTPS",
    "upstreamTls": {
      "enabled": true,
      "sni": "orders.internal.svc",
      "caRef": "/etc/certs/internal-ca.crt"
    },
    "maxRequestBodySize": "5MB",
    "maxResponseBodySize": "20MB",
    "loadBalancing": {
      "algorithm": "ROUND_ROBIN",
      "targets": [
        { "host": "10.0.1.10", "port": 8080, "weight": 10 },
        { "host": "10.0.1.11", "port": 8080, "weight": 10 },
        { "host": "10.0.1.12", "port": 8080, "weight": 10 }
      ]
    },
    "healthCheck": {
      "path": "/actuator/health",
      "interval": "10s",
      "timeout": "3s",
      "healthyThreshold": 2,
      "unhealthyThreshold": 3
    },
    "retry": {
      "retryOn": ["5xx", "connect-failure"],
      "numRetries": 3,
      "perTryTimeout": "2s",
      "retryBackoff": {
        "baseInterval": "100ms",
        "maxInterval": "1s"
      }
    },
    "circuitBreaker": {
      "maxConnections": 1000,
      "minRequestAmount": 20,
      "failureThreshold": 50,
      "resetTimeout": "30s"
    },
    "timeout": {
      "connect": "3s",
      "read": "10s",
      "send": "10s"
    }
  }
}
```

### 4. Router — Listener와 Service 연결

`https-listener`로 들어온 `/api/orders/**` 요청을 `orders-svc`로 라우팅합니다.

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Router",
  "metadata": {
    "name": "route-to-orders"
  },
  "spec": {
    "targetRef": {
      "kind": "Listener",
      "name": "https-listener"
    },
    "rules": [
      {
        "path": "/api/orders(/.*)",
        "methods": ["GET", "POST", "PUT", "DELETE"]
      }
    ],
    "config": {
      "destinations": [
        {
          "destinationRef": {
            "kind": "Service",
            "name": "orders-svc"
          },
          "weight": 100,
          "rewrite": {
            "path": "/v1/orders"
          }
        }
      ]
    }
  }
}
```

### 5. Policy — 보안 (JWT + IP 필터)

`route-to-orders` Router에 부착하여, 주문 API에 JWT 인증과 IP 필터를 적용합니다.

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "metadata": {
    "name": "orders-security"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-orders"
    },
    "policyRef": {
      "name": "security-policy-v1",
      "namespace": "default"
    },
    "order": 5,
    "config": {
      "jwtValidation": {
        "issuer": "https://auth.ecommerce.com",
        "audiences": ["ecommerce-api"],
        "jwksUrl": "https://auth.ecommerce.com/.well-known/jwks.json",
        "clockSkewSeconds": 30,
        "claimsToHeaders": {
          "sub": "X-User-ID",
          "role": "X-User-Role"
        }
      },
      "ipFilter": {
        "allowList": ["10.0.0.0/8", "172.16.0.0/12"]
      }
    }
  }
}
```

### 6. Policy — 트래픽 제어 (Rate Limit + SLA)

같은 Router에 부착하여, 클라이언트별 속도 제한과 VIP 고객 차등 적용을 합니다.

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "metadata": {
    "name": "orders-traffic-control"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-orders"
    },
    "policyRef": {
      "name": "traffic-control-v1",
      "namespace": "default"
    },
    "order": 10,
    "config": {
      "strategy": {
        "type": "header",
        "key": "x-client-id"
      },
      "rateLimit": {
        "quota": { "limit": 1000, "window": "1h" },
        "burst": { "rate": 50, "capacity": 10 }
      },
      "slaTiers": [
        {
          "name": "PREMIUM",
          "match": { "values": ["premium-client-001", "premium-client-002"] },
          "limits": {
            "rateLimit": {
              "quota": { "limit": 10000, "window": "1h" },
              "burst": { "rate": 200, "capacity": 50 }
            }
          }
        }
      ]
    }
  }
}
```

### 전체 구성 요약

```
[Gateway: production-gateway]
  └── logging, tracing, metrics (전역)

[Listener: https-listener]  ──  443/HTTPS, TLS 1.3
  │
  └── [Router: route-to-orders]  ──  /api/orders/** → orders-svc
        │
        ├── [Policy: orders-security]       (order: 5)  ── JWT + IP 필터
        ├── [Policy: orders-traffic-control] (order: 10) ── Rate Limit + SLA
        │
        └── [Service: orders-svc]  ──  3대 백엔드, retry 3회, circuit breaker
```

요청 처리 순서:

1. 클라이언트가 `https://api.ecommerce.com/api/orders`로 요청
2. **Listener** `https-listener`가 443에서 수신, TLS 종료
3. **Router** `route-to-orders`가 경로 매칭
4. **Policy** `orders-security` (order:5) — JWT 검증 + IP 필터
5. **Policy** `orders-traffic-control` (order:10) — Rate Limit 체크
6. **Router**가 경로를 `/v1/orders`로 rewrite
7. **Service** `orders-svc`의 백엔드 타겟으로 전달 (retry, circuit breaker 적용)

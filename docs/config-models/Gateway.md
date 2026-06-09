# Gateway

**개요**

APIM 게이트웨이 전역에 적용되는 설정을 정의합니다.

- **글로벌 정책** (`spec.globalPolicy`): 라우트 매칭 전에 모든 요청에 적용되는 필터 (IP 차단, 전역 Rate Limit, CORS, 요청 크기 제한)
- **관측성** (`spec.logging`, `spec.tracing`, `spec.metrics`): 게이트웨이 전체 로깅, 추적, 메트릭 수집

글로벌 정책은 라우트 매칭 **이전**에 실행됩니다. 여기서 거부된 요청은 Router까지 도달하지 않습니다.

```
요청 → Listener → [글로벌 정책] → Router 매칭 → [라우트 Policy] → Service
                   ^^^^^^^^^^^
                   여기서 거부되면 라우팅 자체를 안 함
```

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류 (Gateway)
metadata.name | Yes | 게이트웨이 설정의 고유 식별자
spec.logging | Yes | 로깅 설정

**타입별 가이드**

유형 | 주요 필드 | 사용 시나리오
---|---|---
IP 차단 | globalPolicy.ipFilter | 알려진 공격 IP 사전 차단
전역 Rate Limit | globalPolicy.rateLimit | 게이트웨이 전체 요청 수 제한
전역 CORS | globalPolicy.cors | 모든 API에 공통 CORS 적용
요청 크기 제한 | globalPolicy.maxRequestBodySize | 대용량 요청 사전 차단
로깅 | logging.accessLog | 접근 로그 수집 및 포맷 설정
추적 | tracing | 분산 추적(OpenTelemetry 등) 연동
메트릭 | metrics | Prometheus 등 메트릭 수집/노출

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Gateway" },
    "metadata": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string" },
        "labels": { "type": "object" }
      }
    },
    "spec": {
      "type": "object",
      "required": ["logging"],
      "properties": {
        "globalPolicy": {
          "type": "object",
          "description": "Executed before route matching on every request",
          "properties": {
            "ipFilter": {
              "type": "object",
              "properties": {
                "denyList": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "pattern": "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(\\/([0-9]|[1-2][0-9]|3[0-2]))?$"
                  },
                  "description": "CIDR list to block before routing"
                },
                "allowList": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "pattern": "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(\\/([0-9]|[1-2][0-9]|3[0-2]))?$"
                  },
                  "description": "If set, only these CIDRs are allowed"
                }
              }
            },
            "rateLimit": {
              "type": "object",
              "properties": {
                "requestsPerSecond": { "type": "integer", "minimum": 1 },
                "burst": { "type": "integer", "minimum": 1 }
              },
              "description": "Global rate limit across all routes"
            },
            "cors": {
              "type": "object",
              "properties": {
                "allowOrigins": { "type": "array", "items": { "type": "string" } },
                "allowMethods": { "type": "array", "items": { "type": "string" } },
                "allowHeaders": { "type": "array", "items": { "type": "string" } },
                "exposeHeaders": { "type": "array", "items": { "type": "string" } },
                "allowCredentials": { "type": "boolean" },
                "maxAge": { "type": "integer", "minimum": 0 }
              },
              "description": "Default CORS applied to all routes (route-level Policy can override)"
            },
            "maxRequestBodySize": {
              "type": "string",
              "pattern": "^[0-9]+(KB|MB|GB)$",
              "default": "10MB",
              "description": "Global request body size limit"
            }
          }
        },
        "logging": {
          "type": "object",
          "properties": {
            "accessLog": {
              "type": "object",
              "properties": {
                "enabled": { "type": "boolean", "default": true },
                "format": { "type": "string", "enum": ["JSON", "TEXT"], "default": "JSON" }
              }
            }
          }
        },
        "tracing": {
          "type": "object",
          "properties": {
            "enabled": { "type": "boolean", "default": false },
            "samplingRate": { "type": "number", "minimum": 0.0, "maximum": 1.0, "default": 0.1 },
            "endpoint": { "type": "string", "format": "uri", "description": "Collector endpoint (e.g. OpenTelemetry)" }
          }
        },
        "metrics": {
          "type": "object",
          "properties": {
            "enabled": { "type": "boolean", "default": false },
            "path": { "type": "string", "default": "/metrics" },
            "port": { "type": "integer", "minimum": 1, "maximum": 65535 }
          }
        }
      }
    }
  }
}
```

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Gateway",
  "metadata": {
    "name": "production-gateway",
    "labels": {
      "env": "production"
    }
  },
  "spec": {
    "globalPolicy": {
      "ipFilter": {
        "denyList": [
          "203.0.113.0/24",
          "198.51.100.50/32"
        ]
      },
      "rateLimit": {
        "requestsPerSecond": 10000,
        "burst": 15000
      },
      "cors": {
        "allowOrigins": ["https://*.ecommerce.com"],
        "allowMethods": ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
        "allowHeaders": ["Content-Type", "Authorization"],
        "allowCredentials": true,
        "maxAge": 3600
      },
      "maxRequestBodySize": "10MB"
    },
    "logging": {
      "accessLog": {
        "enabled": true,
        "format": "JSON"
      }
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

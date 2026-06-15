# Policy - Security

**개요**

API 게이트웨이의 보안을 담당하는 핵심 설정입니다.

이 설정은 "누가(IP), 어떤 권한으로(JWT), 어디서(CORS) 접근 가능한가"를 제어합니다.

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류 (Policy)
metadata.name | Yes | 정책 이름
spec.targetRef.name | Yes | 적용 대상 이름
spec.policyRef.name | Yes | 정책 템플릿 식별자
spec.config | Yes | 보안 설정

**타입별 가이드**

유형 | 주요 필드 | 사용 시나리오
---|---|---
JWT 검증 | jwtValidation | 토큰 기반 인증
IP 제한 | ipFilter | 네트워크 접근 제어
CORS | cors | 브라우저 접근 제어

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Policy" },
    "metadata": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string" }
      }
    },
    "spec": {
      "type": "object",
      "required": ["targetRef", "policyRef", "config"],
      "properties": {
        "targetRef": {
          "type": "object",
          "required": ["name"],
          "properties": {
            "kind": { "type": "string", "const": "Router" },
            "name": { "type": "string" }
          }
        },
        "policyRef": {
          "type": "object",
          "required": ["name"],
          "properties": {
            "name": { "type": "string" },
            "namespace": { "type": "string", "default": "default" }
          }
        },
        "order": {
          "type": "integer",
          "default": 5,
          "description": "Security policies usually have lower order (higher priority) than traffic policies"
        },
        "rules": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "methods": { "type": "string", "pattern": "^[A-Z|]+$" },
              "path": { "type": "string" }
            }
          }
        },
        "config": {
          "type": "object",
          "minProperties": 1,
          "properties": {
            "jwtValidation": {
              "type": "object",
              "required": ["issuer", "jwksUrl"],
              "properties": {
                "issuer": { "type": "string", "format": "uri" },
                "audiences": { "type": "array", "items": { "type": "string" } },
                "jwksUrl": { "type": "string", "format": "uri" },
                "clockSkewSeconds": { "type": "integer", "minimum": 0, "default": 60 },
                "claimsToHeaders": { "type": "object", "additionalProperties": { "type": "string" } }
              }
            },
            "ipFilter": {
              "type": "object",
              "properties": {
                "allowList": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "pattern": "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(\\/([0-9]|[1-2][0-9]|3[0-2]))?$"
                  }
                }
              }
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
              }
            }
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
  "kind": "Policy",
  "metadata": {
    "name": "secure-orders-policy"
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
    "rules": [
      {
        "path": "/api/orders/private(/.*)",
        "methods": "POST|PUT|DELETE"
      }
    ],
    "config": {
      "jwtValidation": {
        "issuer": "https://auth.example.com",
        "audiences": [
          "ecommerce-service",
          "any-api-gateway"
        ],
        "jwksUrl": "https://auth.example.com/.well-known/jwks.json",
        "clockSkewSeconds": 60,
        "claimsToHeaders": {
          "sub": "X-User-ID",
          "email": "X-User-Email",
          "role": "X-User-Role"
        }
      },
      "ipFilter": {
        "allowList": [
          "192.168.1.0/24",
          "10.0.0.5/32"
        ]
      },
      "cors": {
        "allowOrigins": [
          "https://app.example.com",
          "https://admin.example.com"
        ],
        "allowMethods": ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
        "allowHeaders": ["Content-Type", "Authorization", "X-Request-ID"],
        "exposeHeaders": ["X-Total-Count"],
        "allowCredentials": true,
        "maxAge": 3600
      }
    }
  }
}
```

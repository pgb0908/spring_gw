# Policy - Enhance

**개요**

API의 효율을 더 증가시키는 정책을 추가합니다. (캐시 등)

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류 (Policy)
metadata.name | Yes | 정책 이름
spec.targetRef.name | Yes | 적용 대상 이름
spec.policyRef.name | Yes | 정책 템플릿 식별자
spec.config.ttl | Yes | 캐시 기본 TTL
spec.config.strategy | Yes | 캐시 저장소 유형

**타입별 가이드**

유형 | 주요 필드 | 사용 시나리오
---|---|---
Memory 캐시 | strategy=MEMORY | 단일 노드/간단 캐시
Redis/Memcached | strategy, storageRef | 분산 캐시

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
          "default": 12,
          "description": "Caching checks usually happen after traffic control(10) and before transformation(15)"
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
          "required": ["ttl", "strategy"],
          "if": {
            "properties": { "strategy": { "enum": ["REDIS", "MEMCACHED"] } }
          },
          "then": {
            "required": ["ttl", "strategy", "storageRef"]
          },
          "properties": {
            "ttl": {
              "type": "integer",
              "minimum": 1,
              "description": "Default TTL in seconds"
            },
            "strategy": {
              "type": "string",
              "enum": ["MEMORY", "REDIS", "MEMCACHED"],
              "default": "MEMORY"
            },
            "storageRef": {
              "type": "object",
              "required": ["name"],
              "properties": {
                "name": { "type": "string" },
                "namespace": { "type": "string" }
              }
            },
            "keyGeneration": {
              "type": "object",
              "properties": {
                "includeQueryParams": { "type": "array", "items": { "type": "string" } },
                "includeHeaders": { "type": "array", "items": { "type": "string" } },
                "ignoreQueryParams": { "type": "array", "items": { "type": "string" } }
              }
            },
            "conditions": {
              "type": "object",
              "properties": {
                "statusCodes": { "type": "array", "items": { "type": "integer" }, "default": [200] },
                "methods": { "type": "array", "items": { "type": "string", "enum": ["GET", "HEAD"] }, "default": ["GET"] },
                "maxBodySize": { "type": "integer" },
                "negativeCaching": { "type": "object", "additionalProperties": { "type": "integer" } }
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
    "name": "product-catalog-cache"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-products"
    },
    "policyRef": {
      "name": "caching-strategy-v1",
      "namespace": "default"
    },
    "order": 12,
    "rules": [
      {
        "path": "/api/products(/.*)",
        "methods": "GET|HEAD"
      }
    ],
    "config": {
      "ttl": 300,
      "strategy": "REDIS",
      "storageRef": {
        "name": "redis-cluster-prod",
        "namespace": "infra"
      },
      "keyGeneration": {
        "includeQueryParams": [
          "category",
          "page",
          "sort"
        ],
        "includeHeaders": [
          "Accept-Language",
          "Accept-Encoding"
        ],
        "ignoreQueryParams": [
          "utm_source",
          "timestamp"
        ]
      },
      "conditions": {
        "statusCodes": [200, 204],
        "methods": ["GET", "HEAD"],
        "maxBodySize": 1048576,
        "negativeCaching": {
          "404": 60,
          "500": 10
        }
      }
    }
  }
}
```

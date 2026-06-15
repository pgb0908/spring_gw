# Policy - Traffic

**개요**

트래픽 제어(속도 제한, 동시성 제한, SLA 차등 적용)를 담당하는 정책입니다.

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류 (Policy)
metadata.name | Yes | 정책 이름
spec.targetRef.name | Yes | 적용 대상 이름
spec.policyRef.name | Yes | 정책 템플릿 식별자
spec.config | Yes | 트래픽 제어 설정

**타입별 가이드**

유형 | 주요 필드 | 사용 시나리오
---|---|---
기본 Rate Limit | rateLimit | 기본 속도 제한
동시성 제어 | maxConcurrency | 과부하 방지
SLA 차등 | slaTiers | 등급별 제한 분리

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
          "default": 10
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
            "strategy": {
              "type": "object",
              "properties": {
                "type": { "type": "string", "enum": ["client_ip", "header", "jwt_claim"], "default": "client_ip" },
                "key": { "type": "string" }
              },
              "if": {
                "properties": { "type": { "enum": ["header", "jwt_claim"] } }
              },
              "then": {
                "required": ["key"]
              }
            },
            "rateLimit": { "$ref": "#/definitions/limitConfig" },
            "maxConcurrency": {
              "type": "object",
              "properties": {
                "limit": { "type": "integer", "minimum": 1 }
              }
            },
            "slaTiers": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "match", "limits"],
                "properties": {
                  "name": { "type": "string" },
                  "match": {
                    "type": "object",
                    "required": ["values"],
                    "properties": {
                      "values": { "type": "array", "items": { "type": "string" } }
                    }
                  },
                  "limits": {
                    "type": "object",
                    "properties": {
                      "rateLimit": { "$ref": "#/definitions/limitConfig" },
                      "maxConcurrency": {
                        "type": "object",
                        "properties": { "limit": { "type": "integer" } }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  },
  "definitions": {
    "limitConfig": {
      "type": "object",
      "properties": {
        "quota": {
          "type": "object",
          "required": ["limit", "window"],
          "properties": {
            "limit": { "type": "integer", "minimum": 1 },
            "window": { "type": "string", "pattern": "^[0-9]+(ms|s|m|h|d)$" }
          }
        },
        "burst": {
          "type": "object",
          "required": ["rate"],
          "properties": {
            "rate": { "type": "integer", "minimum": 1 },
            "capacity": { "type": "integer", "minimum": 0 }
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
    "name": "secure-write-traffic-control"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-products"
    },
    "policyRef": {
      "name": "traffic-control-v1",
      "namespace": "default"
    },
    "order": 10,
    "rules": [
      {
        "methods": "POST|PUT",
        "path": "/secure(/.*)"
      }
    ],
    "config": {
      "strategy": {
        "type": "header",
        "key": "x-client-id"
      },
      "rateLimit": {
        "quota": {
          "limit": 500,
          "window": "1h"
        },
        "burst": {
          "rate": 20,
          "capacity": 5
        }
      },
      "maxConcurrency": {
        "limit": 50
      },
      "slaTiers": [
        {
          "name": "GOLD",
          "match": {
            "values": ["vip-client-001"]
          },
          "limits": {
            "rateLimit": {
              "quota": { "limit": 5000, "window": "1h" }
            }
          }
        }
      ]
    }
  }
}
```

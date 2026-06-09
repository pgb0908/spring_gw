# Router

**개요**

`Router`는 HTTP 요청 매칭 규칙(`spec.rule.match`)에 따라 요청을 목적지(`spec.destinations`)로 전달하는 리소스입니다.

- 다중 목적지 분산 라우팅: `weight` 사용
- 단일 목적지 라우팅: 목적지 1개만 정의

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | `iip.gateway/v1alpha1`
kind | Yes | `Router`
metadata.name | Yes | Router 리소스 이름
spec.rule.protocol | Yes | 현재 예시는 `HTTP`
spec.rule.match.path | Yes | 매칭 경로
spec.rule.match.methods | Yes | HTTP Method (`GET`, `POST` 등)
spec.destinations | Yes | 목적지 목록

**스키마 (예시 기반)**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Router" },
    "metadata": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string" }
      }
    },
    "spec": {
      "type": "object",
      "required": ["rule", "destinations"],
      "properties": {
        "rule": {
          "type": "object",
          "required": ["protocol", "match"],
          "properties": {
            "protocol": { "type": "string", "enum": ["HTTP"] },
            "match": {
              "type": "object",
              "required": ["path", "methods"],
              "properties": {
                "path": { "type": "string" },
                "methods": {
                  "type": "string",
                  "enum": ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"]
                }
              }
            }
          }
        },
        "destinations": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": ["destinationRef"],
            "properties": {
              "destinationRef": {
                "type": "object",
                "required": ["kind", "name"],
                "properties": {
                  "kind": { "type": "string", "enum": ["Connector", "Flow"] },
                  "name": { "type": "string" }
                }
              },
              "weight": { "type": "integer", "minimum": 0, "maximum": 100 }
            }
          }
        }
      }
    }
  }
}
```

**예시 1: Connector 가중치 분산**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Router",
  "metadata": {
    "name": "testapi1-router"
  },
  "spec": {
    "rule": {
      "protocol": "HTTP",
      "match": {
        "path": "/testapi1",
        "methods": "GET"
      }
    },
    "destinations": [
      {
        "destinationRef": {
          "kind": "Connector",
          "name": "testapi1-connector1"
        },
        "weight": 90
      },
      {
        "destinationRef": {
          "kind": "Connector",
          "name": "testapi1-connector2"
        },
        "weight": 10
      }
    ]
  }
}
```

**예시 2: Flow 목적지 라우팅**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Router",
  "metadata": {
    "name": "testapi2-router"
  },
  "spec": {
    "rule": {
      "protocol": "HTTP",
      "match": {
        "path": "/testapi2",
        "methods": "GET"
      }
    },
    "destinations": [
      {
        "destinationRef": {
          "kind": "Flow",
          "name": "testapi2-flow1"
        }
      }
    ]
  }
}
```

# Router

**개요**

`Router`는 HTTP 요청 매칭 규칙(`spec.rule.match`)에 따라 요청을 목적지(`spec.destinations`)로 전달하는 리소스입니다.

- 다중 목적지 분산 라우팅: `weight` 사용
- 단일 목적지 라우팅: 목적지 1개만 정의

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류
uid | Yes | 리소스 UID
workspaceId | Yes | 워크스페이스 ID
id | Yes | 리소스 ID
name | Yes | 리소스 이름
version | Yes | 리소스 버전 문자열
description | Yes | 리소스 설명
metadata.name | Yes | 메타데이터 이름
spec.rule.protocol | Yes | 현재 예시는 `HTTP`
spec.rule.match.path | Yes | 매칭 경로
spec.rule.match.methods | Yes | HTTP Method (`GET`, `POST` 등)
spec.destinations[].destinationRef.kind | Yes | 목적지 리소스 종류
spec.destinations[].destinationRef.uid | Yes | 목적지 리소스 UID
spec.destinations[].destinationRef.id | Yes | 목적지 리소스 ID
spec.destinations[].destinationRef.name | Yes | 목적지 리소스 이름
spec.destinations | Yes | 목적지 목록

**스키마 (예시 기반)**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "uid", "workspaceId", "id", "name", "version", "description", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Router" },
    "uid": { "type": "string" },
    "workspaceId": { "type": "string" },
    "id": { "type": "string" },
    "name": { "type": "string" },
    "version": { "type": "string" },
    "description": { "type": "string" },
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
                "required": ["kind", "uid", "id", "name"],
                "properties": {
                  "kind": { "type": "string", "enum": ["Connector", "Flow"] },
                  "uid": { "type": "string" },
                  "id": { "type": "string" },
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
  "uid": "UUID",
  "workspaceId": "dev",
  "id": "testapi1-router",
  "name": "testapi1-router",
  "version": "v1",
  "description": "testapi1 router",
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
          "uid": "testapi1-connector1",
          "id": "testapi1-connector1-id",
          "name": "testapi1-connector1-name"
        },
        "weight": 90
      },
      {
        "destinationRef": {
          "kind": "Connector",
          "uid": "testapi1-connector2",
          "id": "testapi1-connector2-id",
          "name": "testapi1-connector2-name"
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
  "uid": "UUID",
  "workspaceId": "dev",
  "id": "testapi2-router",
  "name": "testapi2-router",
  "version": "v1",
  "description": "testapi2 router",
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
          "uid": "testapi2-flow1",
          "id": "testapi2-flow1-id",
          "name": "testapi2-flow1-name"
        }
      }
    ]
  }
}
```

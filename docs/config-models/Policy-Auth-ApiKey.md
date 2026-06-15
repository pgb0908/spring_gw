# Policy - Auth - ApiKey

**개요**

`Policy_auth_apikey`는 `Router`에 연결된 요청에 대해 API Key 헤더를 검증하는 인증 정책 리소스입니다.

- 헤더 기반 인증: `spec.config.header`
- 허용 키 목록 관리: `spec.config.keys`
- 정책 우선순위 제어: `spec.order`

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
spec.targetRef.kind | Yes | 대상 리소스 종류 (`Router`)
spec.targetRef.uid | Yes | 대상 `Router` UID
spec.targetRef.id | Yes | 대상 `Router` ID
spec.targetRef.name | Yes | 대상 `Router` 이름
spec.config.header | Yes | API Key를 읽을 요청 헤더 이름
spec.config.keys | Yes | 허용할 API Key 목록
spec.order | Yes | 정책 적용 순서

**적합한 사용 가이드**

대상 | 주요 필드 | 사용 시나리오
---|---|---
단일 API Key 인증 | `config.header`, `config.keys` | 내부 API 또는 간단한 파트너 연동 인증
다중 클라이언트 키 관리 | `config.keys` | 여러 소비자에게 고정 키를 발급해 접근 제어
보안 정책 우선 적용 | `order` | 다른 트래픽 정책보다 먼저 인증 검사를 수행

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "uid", "workspaceId", "id", "name", "version", "description", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Policy_auth_apikey" },
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
      "required": ["targetRef", "config", "order"],
      "properties": {
        "targetRef": {
          "type": "object",
          "required": ["kind", "uid", "id", "name"],
          "properties": {
            "kind": { "type": "string", "const": "Router" },
            "uid": { "type": "string" },
            "id": { "type": "string" },
            "name": { "type": "string" }
          }
        },
        "config": {
          "type": "object",
          "required": ["header", "keys"],
          "properties": {
            "header": { "type": "string" },
            "keys": {
              "type": "array",
              "minItems": 1,
              "items": { "type": "string" }
            }
          }
        },
        "order": {
          "type": "integer",
          "default": 5
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
  "kind": "Policy_auth_apikey",
  "uid": "UUID",
  "workspaceId": "dev",
  "id": "route-to-orders-api-name",
  "name": "route-to-orders-api-name",
  "version": "1.1.1",
  "description": "testtest",
  "metadata": {
    "name": "route-to-orders-api-key"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "uid": "UUID",
      "id": "route-to-orders-id",
      "name": "route-to-orders-name"
    },
    "config": {
      "header": "x-api-key",
      "keys": [
        "key-abc123",
        "key-def456"
      ]
    },
    "order": 5
  }
}
```

# Policy - Transform

**개요**

메시지의 내용(헤더, 바디, 파라미터)을 변경하는 변환(Transformation) 정책입니다.

이 정책은 "요청이나 응답이 지나갈 때, 데이터를 어떻게 조작할 것인가"를 정의합니다.

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스 버전
kind | Yes | 리소스 종류 (Policy)
metadata.name | Yes | 정책 이름
spec.targetRef.name | Yes | 적용 대상 이름
spec.policyRef.name | Yes | 정책 템플릿 식별자
spec.config | Yes | 변환 설정

**타입별 가이드**

유형 | 주요 필드 | 사용 시나리오
---|---|---
헤더 제어 | headerControl | 헤더 추가/삭제
쿼리 제어 | queryControl | 파라미터 이름 변경/삭제
바디 변환 | bodyTransformation | XML/JSON 변환
마스킹 | dataMasking | 민감 정보 보호

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
          "default": 15,
          "description": "Transformation usually happens after security(5) and traffic(10) checks"
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
            "headerControl": {
              "type": "object",
              "properties": {
                "add": {
                  "type": "object",
                  "additionalProperties": { "type": "string" },
                  "description": "Key-value pairs of headers to inject"
                },
                "remove": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "List of header names to remove"
                }
              }
            },
            "queryControl": {
              "type": "object",
              "properties": {
                "rename": {
                  "type": "object",
                  "additionalProperties": { "type": "string" },
                  "description": "Map: old_param_name -> new_param_name"
                },
                "remove": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              }
            },
            "bodyTransformation": {
              "type": "object",
              "properties": {
                "format": {
                  "type": "string",
                  "enum": ["XML_TO_JSON", "JSON_TO_XML", "NONE"],
                  "default": "NONE"
                }
              }
            },
            "dataMasking": {
              "type": "object",
              "properties": {
                "rules": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["path"],
                    "properties": {
                      "path": { "type": "string", "description": "JSONPath expression" },
                      "pattern": { "type": "string", "format": "regex" },
                      "replacement": { "type": "string", "default": "*****" }
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
}
```

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "metadata": {
    "name": "legacy-adapter-transform"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-legacy-system"
    },
    "policyRef": {
      "name": "legacy-transform-v1",
      "namespace": "default"
    },
    "order": 15,
    "rules": [
      {
        "path": "/api/legacy(/.*)",
        "methods": "POST|PUT"
      }
    ],
    "config": {
      "headerControl": {
        "add": {
          "X-Api-Source": "AnyAPI-Gateway",
          "Content-Type": "application/json"
        },
        "remove": [
          "Server",
          "X-Powered-By",
          "Cookie"
        ]
      },
      "queryControl": {
        "rename": {
          "q": "search_keyword",
          "page": "p"
        },
        "remove": ["debug_mode", "api_key"]
      },
      "bodyTransformation": {
        "format": "XML_TO_JSON"
      },
      "dataMasking": {
        "rules": [
          {
            "path": "$.body.user.ssn",
            "pattern": "\\d{3}-\\d{2}-\\d{4}",
            "replacement": "***-**-****"
          },
          {
            "path": "$.body.credit_card.number",
            "replacement": "****-****-****-****"
          }
        ]
      }
    }
  }
}
```

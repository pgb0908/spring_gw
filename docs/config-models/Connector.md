# Connector

**개요**

`Connector`는 실제 백엔드 API 서버 집합과 연결 정책을 정의하는 리소스입니다.

- **백엔드 연결** (`spec.loadBalancing`, `spec.upstreamTls`): 대상 서버 목록, 로드밸런싱, 업스트림 TLS 설정
- **복원력** (`spec.retry`, `spec.circuitBreaker`): 재시도, 서킷 브레이커, 타임아웃 정책

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
spec.protocol | Yes | 백엔드 호출 프로토콜
spec.proxyPath | Yes | 백엔드 프록시 경로
spec.method | Yes | 백엔드 호출 HTTP Method
spec.loadBalancing.targets | Yes | 백엔드 대상 서버 목록

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "uid", "workspaceId", "id", "name", "version", "description", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Connector" },
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
      "required": ["protocol", "proxyPath", "method", "loadBalancing"],
      "properties": {
        "protocol": {
          "type": "string",
          "enum": ["HTTP", "HTTPS", "GRPC", "TCP"],
          "default": "HTTP"
        },
        "proxyPath": {
          "type": "string"
        },
        "method": {
          "type": "string"
        },
        "requestMsgTpl": {
          "type": "object",
          "required": ["uid", "id", "name"],
          "properties": {
            "uid": { "type": "string" },
            "id": { "type": "string" },
            "name": { "type": "string" }
          }
        },
        "responseMsgTpl": {
          "type": "object",
          "required": ["uid", "id", "name"],
          "properties": {
            "uid": { "type": "string" },
            "id": { "type": "string" },
            "name": { "type": "string" }
          }
        },
        "upstreamTls": {
          "type": "object",
          "properties": {
            "enabled": { "type": "boolean", "default": false },
            "sni": { "type": "string" },
            "caRef": { "type": "string", "description": "CA certificate for backend verification" },
            "clientCertRef": { "type": "string", "description": "Client certificate for mTLS" },
            "clientKeyRef": { "type": "string", "description": "Client private key for mTLS" }
          }
        },
        "maxRequestBodySize": {
          "type": "string",
          "pattern": "^[0-9]+(KB|MB|GB)$",
          "default": "1MB"
        },
        "maxResponseBodySize": {
          "type": "string",
          "pattern": "^[0-9]+(KB|MB|GB)$",
          "default": "10MB"
        },
        "loadBalancing": {
          "type": "object",
          "required": ["targets"],
          "properties": {
            "algorithm": {
              "type": "string",
              "enum": ["ROUND_ROBIN", "LEAST_CONN", "IP_HASH", "RANDOM"],
              "default": "ROUND_ROBIN"
            },
            "targets": {
              "type": "array",
              "minItems": 1,
              "items": {
                "type": "object",
                "required": ["host", "port"],
                "properties": {
                  "host": { "type": "string" },
                  "port": { "type": "integer", "minimum": 1, "maximum": 65535 },
                  "weight": { "type": "integer", "minimum": 1, "default": 1 }
                }
              }
            }
          }
        },
        "healthCheck": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "default": "/" },
            "interval": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" },
            "timeout": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" },
            "healthyThreshold": { "type": "integer", "minimum": 1 },
            "unhealthyThreshold": { "type": "integer", "minimum": 1 }
          }
        },
        "retry": {
          "type": "object",
          "properties": {
            "retryOn": {
              "type": "array",
              "items": {
                "type": "string",
                "enum": ["5xx", "reset", "connect-failure", "retriable-4xx", "refused-stream", "gateway-error"]
              },
              "default": ["5xx", "connect-failure"]
            },
            "numRetries": { "type": "integer", "minimum": 0, "maximum": 10, "default": 3 },
            "perTryTimeout": { "type": "string", "pattern": "^[0-9]+(ms|s|m|h)$" },
            "retryBackoff": {
              "type": "object",
              "properties": {
                "baseInterval": { "type": "string", "pattern": "^[0-9]+(ms|s)$", "default": "100ms" },
                "maxInterval": { "type": "string", "pattern": "^[0-9]+(ms|s)$", "default": "1s" }
              }
            }
          }
        },
        "circuitBreaker": {
          "type": "object",
          "properties": {
            "maxConnections": { "type": "integer" },
            "minRequestAmount": { "type": "integer" },
            "failureThreshold": { "type": "integer", "description": "Failure count or percentage" },
            "resetTimeout": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" }
          }
        },
        "timeout": {
          "type": "object",
          "properties": {
            "connect": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" },
            "read": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" },
            "send": { "type": "string", "pattern": "^[0-9]+(s|m|ms)$" }
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
  "kind": "Connector",
  "uid": "UUID",
  "workspaceId": "dev",
  "id": "products-backend-cluster",
  "name": "products-backend-cluster",
  "version": "v1",
  "description": "products backend connector",
  "metadata": {
    "name": "products-backend-cluster"
  },
  "spec": {
    "protocol": "HTTPS",
    "proxyPath": "/products",
    "method": "GET",
    "requestMsgTpl": {
      "uid": "request-template-uuid",
      "id": "request-template-id",
      "name": "request-template-name"
    },
    "responseMsgTpl": {
      "uid": "response-template-uuid",
      "id": "response-template-id",
      "name": "response-template-name"
    },
    "upstreamTls": {
      "enabled": true,
      "sni": "products.internal.svc",
      "caRef": "/etc/certs/upstream-ca.crt"
    },
    "maxRequestBodySize": "5MB",
    "maxResponseBodySize": "50MB",
    "loadBalancing": {
      "algorithm": "ROUND_ROBIN",
      "targets": [
        {
          "host": "10.0.1.10",
          "port": 8080,
          "weight": 5
        },
        {
          "host": "10.0.1.11",
          "port": 8080,
          "weight": 10
        }
      ]
    },
    "healthCheck": {
      "path": "/actuator/health",
      "interval": "15s",
      "timeout": "5s",
      "healthyThreshold": 2,
      "unhealthyThreshold": 3
    },
    "retry": {
      "retryOn": ["5xx", "connect-failure", "refused-stream"],
      "numRetries": 3,
      "perTryTimeout": "2s",
      "retryBackoff": {
        "baseInterval": "100ms",
        "maxInterval": "1s"
      }
    },
    "circuitBreaker": {
      "maxConnections": 1000,
      "minRequestAmount": 10,
      "failureThreshold": 50,
      "resetTimeout": "30s"
    },
    "timeout": {
      "connect": "5s",
      "read": "10s",
      "send": "10s"
    }
  }
}
```

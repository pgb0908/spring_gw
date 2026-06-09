# Listener

**개요**

게이트웨이가 리스닝할 포트와 프로토콜을 정의합니다.

**필수 필드**

필드명 | 필수 | 설명
---|---|---
apiVersion | Yes | 리소스의 버전입니다. (예: iip.gateway/v1alpha1)
kind | Yes | 리소스 종류입니다. (Listener)
metadata.name | Yes | 리스너의 고유 식별자입니다.
spec.protocol | Yes | 통신 프로토콜 (HTTP, HTTPS, TCP, GRPC)
spec.port | Yes | 바인딩할 포트 번호

**타입별 가이드**

타입 | 주요 필드 | 사용 시나리오
---|---|---
HTTP/HTTPS | tls, connection, allowedHostnames | 일반적인 HTTP(S) 게이트웨이 구성
TCP/GRPC | connection | L4/TCP 혹은 GRPC 리스닝

**스키마**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["apiVersion", "kind", "metadata", "spec"],
  "properties": {
    "apiVersion": { "type": "string", "const": "iip.gateway/v1alpha1" },
    "kind": { "type": "string", "const": "Listener" },
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
      "required": ["protocol", "port"],
      "properties": {
        "protocol": {
          "type": "string",
          "enum": ["HTTP", "HTTPS", "TCP", "GRPC"]
        },
        "port": {
          "type": "integer",
          "minimum": 1,
          "maximum": 65535
        },
        "host": {
          "type": "string",
          "default": "0.0.0.0",
          "pattern": "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"
        },
        "allowedHostnames": {
          "type": "array",
          "items": { "type": "string" }
        },
        "tls": {
          "type": "object",
          "properties": {
            "mode": { "type": "string", "enum": ["TERMINATE", "PASSTHROUGH"] },
            "minVersion": { "type": "string", "enum": ["1.0", "1.1", "1.2", "1.3"] },
            "certificates": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["certRef", "keyRef"],
                "properties": {
                  "certRef": { "type": "string" },
                  "keyRef": { "type": "string" }
                }
              }
            }
          }
        },
        "connection": {
          "type": "object",
          "properties": {
            "readTimeout": { "type": "string", "pattern": "^[0-9]+(s|m|h)$" },
            "writeTimeout": { "type": "string", "pattern": "^[0-9]+(s|m|h)$" },
            "maxConnections": { "type": "integer" }
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
  "kind": "Listener",
  "metadata": {
    "name": "ecommerce-https-listener",
    "labels": {
      "env": "production"
    }
  },
  "spec": {
    "protocol": "HTTPS",
    "port": 8443,
    "host": "0.0.0.0",
    "allowedHostnames": [
      "api.ecommerce.com",
      "payment.ecommerce.com"
    ],
    "tls": {
      "mode": "TERMINATE",
      "minVersion": "1.3",
      "certificates": [
        {
          "certRef": "/etc/certs/tls.crt",
          "keyRef": "/etc/certs/tls.key"
        }
      ]
    },
    "connection": {
      "readTimeout": "10s",
      "writeTimeout": "10s",
      "maxConnections": 10000
    }
  }
}
```

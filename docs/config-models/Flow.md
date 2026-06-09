# Flow

**개요**

`Flow`는 Integration 엔진과의 통신을 정의한 리로스 입니다.

**필수 필드**


**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Flow",
  "metadata": {
    "name": "flow1",
    "labels": {
      "app": "flow-service",
      "env": "prod"
    }
  },
  "spec": {
    "protocol": "gRPC",
    "upstreamTls": {
      "enabled": false
    },
    "loadBalancing": {
      "algorithm": "ROUND_ROBIN",
      "targets": [
        {
          "host": "10.0.1.10",
          "port": 8080,
          "flow-id": "111111"
        }
      ]
    },
    "timeout": {
      "connect": "5s",
      "read": "10s",
      "send": "10s"
    }
  }
}
```
# Policy - Security

**개요**

Router에 부착되는 per-route 보안 필터 설정 리소스. Policy 하나가 필터 하나에 대응하며, `type` 필드가 적용할 필터를 결정한다.

지원 필터 타입:

| type | 동작 |
|---|---|
| `IpFilter` | 클라이언트 IP를 allowList와 대조해 차단 |
| `JwtValidation` | Bearer JWT를 RSA 공개키로 검증하고 claim을 헤더로 주입 |
| `ApiKeyAuth` | 지정 헤더의 API Key를 허용 목록과 대조해 인증 |

**공통 필드**

| 필드 | 필수 | 설명 |
|---|---|---|
| `apiVersion` | Yes | 리소스 버전 |
| `kind` | Yes | `"Policy"` 고정 |
| `type` | Yes | 필터 이름 (`IpFilter` \| `JwtValidation` \| `ApiKeyAuth`) |
| `metadata.name` | Yes | Policy 이름 |
| `spec.targetRef.name` | Yes | 적용 대상 Router 이름 |
| `spec.order` | No | 실행 순서 오름차순 (기본값 `0`) |
| `spec.config` | Yes | 필터별 설정 (아래 타입별 스키마 참조) |

> `spec.targetRef.kind`는 항상 `"Router"`이며 생략 가능.

---

## IpFilter

클라이언트 IP가 `allowList`에 포함되지 않으면 `403 Forbidden` 반환. `allowList`가 비어있으면 모든 IP 허용.

**spec.config 스키마**

```json
{
  "allowList": ["<IP 또는 CIDR>"]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `allowList` | `string[]` | 허용할 IP 또는 CIDR 목록. 생략 시 전체 허용 |

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "type": "IpFilter",
  "metadata": {
    "name": "orders-ip-filter"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-orders"
    },
    "order": 5,
    "config": {
      "allowList": [
        "10.0.0.0/8",
        "192.168.1.100"
      ]
    }
  }
}
```

---

## JwtValidation

`Authorization: Bearer <token>` 헤더를 검증한다. 토큰이 없거나 서명 검증에 실패하거나 만료된 경우 `401 Unauthorized` 반환. 검증 성공 시 `claimsToHeaders` 매핑에 따라 claim 값을 요청 헤더로 주입한다.

**spec.config 스키마**

```json
{
  "publicKey": { "<RSA JWK>" },
  "claimsToHeaders": {
    "<claim 이름>": "<헤더 이름>"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `publicKey` | RSA JWK object | Yes | JWT 서명 검증에 사용할 RSA 공개키 (JWK 포맷) |
| `claimsToHeaders` | `object` | No | claim → 요청 헤더 매핑. claim 값이 없으면 해당 헤더 생략 |

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "type": "JwtValidation",
  "metadata": {
    "name": "orders-jwt"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-orders"
    },
    "order": 10,
    "config": {
      "publicKey": {
        "kty": "RSA",
        "n": "...",
        "e": "AQAB"
      },
      "claimsToHeaders": {
        "sub": "X-User-ID",
        "email": "X-User-Email"
      }
    }
  }
}
```

---

## ApiKeyAuth

지정한 요청 헤더에서 API Key를 읽어 `keys` 목록과 대조한다. 헤더가 없거나 목록에 없는 키이면 `401 Unauthorized` 반환.

**spec.config 스키마**

```json
{
  "header": "<헤더 이름>",
  "keys": ["<key1>", "<key2>"]
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `header` | `string` | No | API Key를 읽을 요청 헤더 이름. 기본값 `X-API-Key` |
| `keys` | `string[]` | Yes | 허용할 API Key 목록. 하나 이상 필수 |

**예시**

```json
{
  "apiVersion": "iip.gateway/v1alpha1",
  "kind": "Policy",
  "type": "ApiKeyAuth",
  "metadata": {
    "name": "orders-api-key"
  },
  "spec": {
    "targetRef": {
      "kind": "Router",
      "name": "route-to-orders"
    },
    "order": 5,
    "config": {
      "header": "X-API-Key",
      "keys": [
        "service-a-key-abc123",
        "service-b-key-def456"
      ]
    }
  }
}
```

---

## 여러 Policy를 한 Router에 부착하는 경우

Policy는 Router를 참조하며 Router는 Policy를 알지 못한다. 같은 Router를 참조하는 여러 Policy를 정의하면 `spec.order` 오름차순으로 순차 실행된다.

```json
// order 5: IP 차단 먼저
{ "type": "IpFilter", "spec": { "targetRef": { "name": "route-to-orders" }, "order": 5, ... } }

// order 10: JWT 검증 이후
{ "type": "JwtValidation", "spec": { "targetRef": { "name": "route-to-orders" }, "order": 10, ... } }
```

#!/usr/bin/env python3
"""
Mock Flow Engine (Core) — ADR 0008 / 0009 검증용
포트: 9001

엔드포인트:
  POST /core/flows/start          — GW가 StartFlow 호출 → RUNNING ACK 즉시 반환 → 지연 후 /gateway/ingress/response 콜백
  POST /core/flows/response-ack   — GW가 ResponseAck fire-and-forget으로 전송
  POST /gateway/connector/response — GW가 Connector 호출 완료 후 결과를 콜백
  POST /trigger-connector         — (수동 트리거) ADR 0009 테스트: GW Egress로 CONNECTOR_REQUEST 전송
  GET  /health                    — 헬스체크
"""

import json
import time
import uuid
import base64
import threading
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler

CORE_PORT      = int(__import__('os').environ.get('MOCK_CORE_PORT', '9001'))
GW_EGRESS_HOST = __import__('os').environ.get('GW_EGRESS_HOST', 'localhost')
GW_EGRESS_PORT = int(__import__('os').environ.get('GW_EGRESS_PORT', '8090'))
RESPONSE_DELAY = float(__import__('os').environ.get('RESPONSE_DELAY_SEC', '0.5'))


def _post(url: str, body: dict):
    data = json.dumps(body).encode()
    req  = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            print(f"  [callback ok] {url} → {resp.status} {raw[:120]}")
    except urllib.error.HTTPError as e:
        print(f"  [callback err] {url} → HTTP {e.code} {e.read().decode()[:120]}")
    except Exception as e:
        print(f"  [callback err] {url} → {e}")


def _send_ingress_response(envelope: dict):
    time.sleep(RESPONSE_DELAY)
    guid        = envelope.get('guid', '')
    payload_str = json.dumps({"message": "hello from mock-core", "guid": guid, "adr": "0008"})
    payload_b64 = base64.b64encode(payload_str.encode()).decode()

    response = {
        "guid":       guid,
        "status":     "RUNNING",
        "action":     "RESPONSE_REQUEST",
        "flow_id":    envelope.get('flow_id'),
        "core_id":    envelope.get('core_id'),
        "gateway_id": envelope.get('gateway_id'),
        "payload":    payload_b64,
        "content_type": "application/json",
        "finished_at": int(time.time() * 1000),
        "error_code":    "",
        "error_message": ""
    }
    url = f"http://{GW_EGRESS_HOST}:{GW_EGRESS_PORT}/gateway/ingress/response"
    print(f"[mock-core] → SendResponse guid={guid} url={url}")
    _post(url, response)


class CoreHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # suppress default access log; we print manually

    def _read_body(self) -> dict:
        length = int(self.headers.get('Content-Length', 0))
        raw    = self.rfile.read(length) if length else b'{}'
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def _reply(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self._reply(200, {"status": "UP", "service": "mock-core"})
        else:
            self._reply(404, {"error": "not found"})

    def do_POST(self):
        body = self._read_body()

        # ── ADR 0008: GW가 StartFlow 전송
        if self.path == '/core/flows/start':
            guid    = body.get('guid', str(uuid.uuid4()))
            flow_id = body.get('flow_id', '')
            print(f"[mock-core] ← StartFlow  guid={guid}  flow_id={flow_id}")
            ack = {"guid": guid, "status": "RUNNING", "error_code": "", "error_message": ""}
            self._reply(200, ack)
            # 지연 후 역방향 콜백 (별도 스레드)
            threading.Thread(target=_send_ingress_response, args=(body,), daemon=True).start()

        # ── ADR 0008: GW가 ResponseAck fire-and-forget
        elif self.path == '/core/flows/response-ack':
            guid = body.get('guid', '')
            print(f"[mock-core] ← ResponseAck guid={guid}  ✓")
            self._reply(200, {"status": "OK"})

        # ── ADR 0009: GW가 Connector 결과를 콜백
        elif self.path == '/gateway/connector/response':
            guid         = body.get('guid', '')
            status       = body.get('status', '')
            connector_id = body.get('connector_id', '')
            payload_b64  = body.get('payload', '')
            try:
                payload_decoded = base64.b64decode(payload_b64).decode() if payload_b64 else ''
            except Exception:
                payload_decoded = payload_b64
            print(f"[mock-core] ← ConnectorResponse guid={guid}  connector={connector_id}  status={status}")
            print(f"             payload: {payload_decoded[:200]}")
            self._reply(200, {"guid": guid, "status": "RUNNING", "error_code": "", "error_message": ""})

        # ── ADR 0009 수동 트리거: 이 엔드포인트를 curl로 호출하면 GW Egress에 CONNECTOR_REQUEST를 보냄
        elif self.path == '/trigger-connector':
            connector_id = body.get('connector_id', 'example-connector')
            custom_payload = body.get('payload', '{"request": "from mock-core", "adr": "0009"}')
            req_guid = str(uuid.uuid4())
            payload_b64 = base64.b64encode(
                (custom_payload if isinstance(custom_payload, str)
                 else json.dumps(custom_payload)).encode()
            ).decode()

            connector_request = {
                "guid":         req_guid,
                "action":       "CONNECTOR_REQUEST",
                "status":       "RUNNING",
                "flow_id":      "example-flow",
                "core_id":      "core-1",
                "connector_id": connector_id,
                "payload":      payload_b64,
                "header":       {"Content-Type": "application/json"},
                "started_at":   int(time.time() * 1000),
                "error_code":   "",
                "error_message": ""
            }
            url = f"http://{GW_EGRESS_HOST}:{GW_EGRESS_PORT}/gateway/connector/request"
            print(f"[mock-core] → ConnectorRequest guid={req_guid}  connector={connector_id}  url={url}")
            self._reply(200, {"triggered": True, "guid": req_guid})
            threading.Thread(target=_post, args=(url, connector_request), daemon=True).start()

        else:
            self._reply(404, {"error": f"unknown path: {self.path}"})


if __name__ == '__main__':
    server = HTTPServer(('0.0.0.0', CORE_PORT), CoreHandler)
    print(f"[mock-core] 기동 — port={CORE_PORT}")
    print(f"  GW Egress 콜백 주소: http://{GW_EGRESS_HOST}:{GW_EGRESS_PORT}")
    print(f"  StartFlow 응답 지연: {RESPONSE_DELAY}s")
    print()
    print("  엔드포인트:")
    print(f"    POST /core/flows/start          ← GW StartFlow (ADR 0008)")
    print(f"    POST /core/flows/response-ack   ← GW ResponseAck (ADR 0008)")
    print(f"    POST /gateway/connector/response ← GW ConnectorCallback (ADR 0009)")
    print(f"    POST /trigger-connector          → GW ConnectorRequest 수동 트리거 (ADR 0009)")
    print(f"    GET  /health")
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-core] 종료")

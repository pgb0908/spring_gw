#!/usr/bin/env python3
"""
Mock Backend Service — ADR 0009 검증용
포트: 9002

GW가 Connector를 통해 실제로 호출하는 외부 백엔드 서비스를 모방한다.
모든 POST 요청에 JSON 응답을 반환하고 요청 내용을 출력한다.
"""

import json
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

BACKEND_PORT = int(__import__('os').environ.get('MOCK_BACKEND_PORT', '9002'))
DELAY        = float(__import__('os').environ.get('BACKEND_DELAY_SEC', '0.2'))


class BackendHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _read_body(self) -> bytes:
        length = int(self.headers.get('Content-Length', 0))
        return self.rfile.read(length) if length else b''

    def _reply(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('X-Mock-Backend', 'true')
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self._reply(200, {"status": "UP", "service": "mock-backend"})
        else:
            self._reply(200, {"method": "GET", "path": self.path, "service": "mock-backend"})

    def do_POST(self):
        raw = self._read_body()
        headers_received = {k: v for k, v in self.headers.items()}

        try:
            body_str = raw.decode()
        except Exception:
            body_str = repr(raw)

        print(f"[mock-backend] ← POST {self.path}")
        print(f"  headers: { {k: v for k, v in headers_received.items() if k.lower() not in ('host',)} }")
        print(f"  body   : {body_str[:300]}")

        if DELAY > 0:
            time.sleep(DELAY)

        response = {
            "result":       "success",
            "service":      "mock-backend",
            "path":         self.path,
            "received_at":  int(time.time() * 1000),
            "echo":         body_str[:100]
        }
        self._reply(200, response)
        print(f"  → 200 OK")


if __name__ == '__main__':
    server = HTTPServer(('0.0.0.0', BACKEND_PORT), BackendHandler)
    print(f"[mock-backend] 기동 — port={BACKEND_PORT}")
    print(f"  응답 지연: {DELAY}s")
    print(f"  모든 POST/GET 요청에 200 JSON 응답")
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-backend] 종료")

#!/bin/bash
# ADR 0008 / ADR 0009 수동 검증 스크립트
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GW_HOST="${GW_HOST:-localhost}"
GW_PORT="${GW_PORT:-8080}"
CORE_PORT="${MOCK_CORE_PORT:-9001}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

step() { echo -e "\n${CYAN}▶ $1${NC}"; }
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; }

check_running() {
    local name="$1" pid_file="$SCRIPT_DIR/${1}.pid"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        ok "$name 실행 중 (PID $(cat "$pid_file"))"
    else
        fail "$name 실행 중이 아님 → ./mock/start-mock.sh 먼저 실행"
        PREREQ_FAILED=1
    fi
}

# ── 사전 조건 확인 ──────────────────────────────────────────────────
step "사전 조건 확인"
PREREQ_FAILED=0
check_running "mock-core"
check_running "mock-backend"

if ! curl -sf "http://${GW_HOST}:${GW_PORT}/actuator/health" > /dev/null 2>&1; then
    fail "Gateway 응답 없음 (http://${GW_HOST}:${GW_PORT}) → ./start.sh 먼저 실행"
    PREREQ_FAILED=1
else
    ok "Gateway 실행 중"
fi

[ "$PREREQ_FAILED" -eq 1 ] && echo -e "\n기동 순서:\n  ./mock/start-mock.sh\n  ./start.sh\n" && exit 1

# ── ADR 0008: Ingress 비동기 흐름 ──────────────────────────────────
echo -e "\n${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW} ADR 0008 — Gateway-Flow 비동기 요청-응답${NC}"
echo -e "${YELLOW} 흐름: Client → GW:${GW_PORT} → mock-core:${CORE_PORT} (StartFlow)${NC}"
echo -e "${YELLOW}       mock-core → GW:8090/gateway/ingress/response → Client 응답${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

step "POST /api/flow/test 전송"
echo "  curl -X POST http://${GW_HOST}:${GW_PORT}/api/flow/test \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"hello\": \"world\"}'"
echo ""

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://${GW_HOST}:${GW_PORT}/api/flow/test" \
    -H "Content-Type: application/json" \
    -d '{"hello": "world"}' 2>&1)

HTTP_BODY=$(echo "$RESPONSE" | head -n -1)
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)

echo "  HTTP 상태: $HTTP_CODE"
echo "  응답 본문: $HTTP_BODY"

if [ "$HTTP_CODE" = "200" ]; then
    ok "ADR 0008 검증 성공 — 비동기 StartFlow → SendResponse 흐름 정상"
else
    fail "ADR 0008 검증 실패 (HTTP $HTTP_CODE)"
fi

# ── ADR 0009: Egress Connector 비동기 흐름 ─────────────────────────
echo -e "\n${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW} ADR 0009 — Egress Connector 비동기 HTTP${NC}"
echo -e "${YELLOW} 흐름: mock-core:${CORE_PORT}/trigger-connector${NC}"
echo -e "${YELLOW}       → GW:8090/gateway/connector/request (CONNECTOR_REQUEST)${NC}"
echo -e "${YELLOW}       → mock-backend:9002 (실제 HTTP 호출)${NC}"
echo -e "${YELLOW}       → mock-core:${CORE_PORT}/gateway/connector/response (콜백)${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

step "mock-core에 CONNECTOR_REQUEST 트리거"
echo "  curl -X POST http://localhost:${CORE_PORT}/trigger-connector \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"connector_id\": \"example-connector\", \"payload\": {\"data\": \"test\"}}'"
echo ""

TRIGGER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:${CORE_PORT}/trigger-connector" \
    -H "Content-Type: application/json" \
    -d '{"connector_id": "example-connector", "payload": {"data": "test"}}' 2>&1)

TRIGGER_BODY=$(echo "$TRIGGER_RESPONSE" | head -n -1)
TRIGGER_CODE=$(echo "$TRIGGER_RESPONSE" | tail -n 1)

echo "  HTTP 상태: $TRIGGER_CODE"
echo "  응답 본문: $TRIGGER_BODY"

if [ "$TRIGGER_CODE" = "200" ]; then
    ok "트리거 전송 성공 — mock-core.log 와 mock-backend.log 에서 콜백 수신 확인"
    echo ""
    warn "비동기 흐름이므로 콜백 수신까지 약 1초 대기 후 로그 확인:"
    echo "  tail mock/mock-core.log"
    echo "  tail mock/mock-backend.log"
else
    fail "트리거 실패 (HTTP $TRIGGER_CODE)"
fi

# ── 로그 위치 안내 ──────────────────────────────────────────────────
echo ""
step "로그 파일"
echo "  mock/mock-core.log    — StartFlow 수신, ConnectorResponse 콜백 수신 확인"
echo "  mock/mock-backend.log — GW가 실제로 백엔드를 호출했는지 확인"
echo "  spring-gw.log         — GW 내부 흐름 추적"

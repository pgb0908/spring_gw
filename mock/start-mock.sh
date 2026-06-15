#!/bin/bash
# Mock 서버 두 개를 백그라운드로 기동
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

start_mock() {
    local name="$1"
    local script="$2"
    local pid_file="$SCRIPT_DIR/${name}.pid"
    local log_file="$SCRIPT_DIR/${name}.log"

    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "[${name}] 이미 실행 중 (PID $(cat "$pid_file"))"
        return
    fi

    nohup python3 -u "$SCRIPT_DIR/$script" > "$log_file" 2>&1 &
    echo $! > "$pid_file"
    echo "[${name}] 기동 완료 (PID $(cat "$pid_file")) — 로그: ${log_file}"
}

start_mock "mock-core"    "mock-core.py"
start_mock "mock-backend" "mock-backend.py"

echo ""
echo "포트 구성:"
echo "  9001  mock-core    (Flow 엔진 — StartFlow 수신, ConnectorResponse 수신)"
echo "  9002  mock-backend (외부 백엔드 — Connector가 실제로 호출하는 서비스)"
echo ""
echo "다음 단계:"
echo "  ./start.sh          GW 기동"
echo "  ./mock/test.sh      ADR 0008 / 0009 자동 검증"
echo "  ./mock/stop-mock.sh 종료"

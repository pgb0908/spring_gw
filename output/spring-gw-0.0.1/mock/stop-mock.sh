#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

stop_mock() {
    local name="$1"
    local pid_file="$SCRIPT_DIR/${name}.pid"

    if [ -f "$pid_file" ]; then
        PID="$(cat "$pid_file")"
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" && echo "[${name}] 종료 (PID ${PID})"
        else
            echo "[${name}] 이미 종료됨"
        fi
        rm -f "$pid_file"
    else
        echo "[${name}] PID 파일 없음"
    fi
}

stop_mock "mock-core"
stop_mock "mock-backend"

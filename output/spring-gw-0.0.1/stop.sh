#!/bin/bash
PID_FILE="$(cd "$(dirname "$0")" && pwd)/spring-gw.pid"
if [ -f "$PID_FILE" ]; then
    kill "$(cat "$PID_FILE")" && rm -f "$PID_FILE" && echo "stopped"
else
    echo "PID file not found"
fi

#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$SCRIPT_DIR"/*.jar | head -1)
PID_FILE="$SCRIPT_DIR/spring-gw.pid"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Already running (PID $(cat "$PID_FILE"))"
    exit 1
fi

nohup java ${JAVA_OPTS} -jar "$JAR" "$@" > "$SCRIPT_DIR/spring-gw.log" 2>&1 &
echo $! > "$PID_FILE"
echo "Started (PID $(cat "$PID_FILE"))"

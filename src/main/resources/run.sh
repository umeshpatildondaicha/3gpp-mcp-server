#!/bin/sh
# Container startup script — called as: sh run.sh start

APP_JAR="${SERVICE_PATH}/3gpp-mcp-server-2.0.0.jar"
AGENT_JAR="${SERVICE_PATH}/agent/skywalking-agent.jar"
CONFIG="${SERVICE_PATH}/application.properties"
PORT="${MELODY_PORT:-8080}"

# Build SkyWalking agent opts if agent exists
SW_OPTS=""
if [ -f "$AGENT_JAR" ]; then
    SW_OPTS="-javaagent:${AGENT_JAR} \
        -Dskywalking.agent.service_name=${SW_AGENT_NAME:-3gpp-mcp} \
        -Dskywalking.collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:-skywalking-oap-server:11800}"
fi

if [ "$1" = "start" ]; then
    echo "[$(date '+%H:%M:%S')] Starting 3gpp-mcp on port ${PORT}"
    exec java ${JAVA_OPTS} ${SW_OPTS} \
        -jar "${APP_JAR}" \
        --server.port="${PORT}" \
        --spring.config.location="${CONFIG}"
fi

echo "Usage: sh run.sh start"
exit 1

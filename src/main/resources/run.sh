#!/bin/sh
# Container startup script — called as: sh run.sh start

APP_JAR="${SERVICE_PATH}/3gpp-mcp-server-2.0.0.jar"
AGENT_JAR="${SERVICE_PATH}/agent/skywalking-agent.jar"
CONFIG="${SERVICE_PATH}/application.properties"
PORT="${MELODY_PORT:-${PORT:-3000}}"

# When the image was built with `mvn -P bake-db package`, both DBs live next to
# the JAR. Default KB_DB_PATH / KB_DB_PATH2 to those paths so the container
# boots without requiring a mounted volume. Operators can still override either
# by exporting the env var (e.g. to point at a writable PVC for ingest updates).
BAKED_KB_DIR="${SERVICE_PATH}/3gpp_kb"
if [ -f "${BAKED_KB_DIR}/3gpp_certified.db" ] && [ -z "${KB_DB_PATH}" ]; then
    export KB_DB_PATH="${BAKED_KB_DIR}/3gpp_certified.db"
    echo "[run.sh] using baked-in primary DB: ${KB_DB_PATH}"
fi
if [ -f "${BAKED_KB_DIR}/telecom_extras.db" ] && [ -z "${KB_DB_PATH2}" ]; then
    export KB_DB_PATH2="${BAKED_KB_DIR}/telecom_extras.db"
    echo "[run.sh] using baked-in extras DB:  ${KB_DB_PATH2}"
fi

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

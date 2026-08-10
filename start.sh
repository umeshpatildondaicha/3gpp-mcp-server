#!/usr/bin/env bash
# Launch the 3GPP MCP server with the right env vars.
#
# Usage:
#   ./start.sh              # dev mode (mvn spring-boot:run, hot reload of resources)
#   ./start.sh jar          # run the packaged JAR from target/docker/
#   ./start.sh build        # rebuild the JAR (mvn -DskipTests package), then run
#
# Override paths by exporting before invoking, e.g.:
#   KB_DB_PATH=/path/to/3gpp_certified.db ./start.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Default DB locations live alongside the project; override via env if needed.
export KB_DB_PATH="${KB_DB_PATH:-$PROJECT_DIR/3gpp_kb/3gpp_certified.db}"
export KB_DB_PATH2="${KB_DB_PATH2:-$PROJECT_DIR/3gpp_kb/telecom_extras.db}"

# Sanity-check both DBs before we hand off to the JVM — DbResolver would only
# log a generic "Classpath DB resource not found" error otherwise.
for var in KB_DB_PATH KB_DB_PATH2; do
    path="${!var}"
    if [[ ! -f "$path" ]]; then
        echo "ERROR: $var=$path does not exist." >&2
        echo "       Set the env var to the correct path, or place the DB at the default location." >&2
        exit 1
    fi
done

# If port 3000 is already bound, refuse to start a second copy.
if lsof -nP -iTCP:3000 -sTCP:LISTEN -t >/dev/null 2>&1; then
    pid=$(lsof -nP -iTCP:3000 -sTCP:LISTEN -t | head -1)
    echo "ERROR: port 3000 is already in use by PID $pid." >&2
    echo "       Stop it first:  kill -TERM $pid" >&2
    exit 1
fi

mode="${1:-dev}"
echo "[start.sh] KB_DB_PATH  = $KB_DB_PATH"
echo "[start.sh] KB_DB_PATH2 = $KB_DB_PATH2"

case "$mode" in
    dev)
        echo "[start.sh] launching via mvn spring-boot:run ..."
        exec mvn -q spring-boot:run
        ;;
    jar)
        jar="$PROJECT_DIR/target/docker/3gpp-mcp-server-2.0.0.jar"
        [[ -f "$jar" ]] || { echo "ERROR: JAR not found at $jar — run './start.sh build' first." >&2; exit 1; }
        echo "[start.sh] launching $jar ..."
        exec java -jar "$jar"
        ;;
    build)
        echo "[start.sh] building JAR ..."
        mvn -q -DskipTests package
        jar="$PROJECT_DIR/target/3gpp-mcp-server-2.0.0.jar"
        [[ -f "$jar" ]] || { echo "ERROR: build did not produce $jar" >&2; exit 1; }
        echo "[start.sh] launching $jar ..."
        exec java -jar "$jar"
        ;;
    *)
        echo "Usage: $0 [dev|jar|build]" >&2
        exit 2
        ;;
esac

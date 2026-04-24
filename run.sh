#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
APP_NAME="3gpp-mcp"
APP_VERSION="2.0.0"
JAR_NAME="3gpp-mcp-server-${APP_VERSION}.jar"

REGISTRY="container.visionwaves.com"
IMAGE="${REGISTRY}/visionwaves/${APP_NAME}:${IMAGE_TAG:-V.1.0}"

NAMESPACE="${NAMESPACE:-ansible}"
DEPLOYMENT="3gpp-mcp-service"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[ERROR] $*" >&2; exit 1; }

# ── 1. Maven build ────────────────────────────────────────────────────────────
log "Building JAR with Maven (linux/amd64)..."
cd "$PROJECT_DIR"
mvn -q -DskipTests package

JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
[ -f "$JAR_PATH" ] || fail "JAR not found: ${JAR_PATH}"
log "JAR built: ${JAR_PATH}"

# ── 2. Assemble Docker build context ──────────────────────────────────────────
BUILD_DIR="${PROJECT_DIR}/target/docker-context"
TAR_STAGING="${BUILD_DIR}/tar-staging"

log "Assembling build context at ${BUILD_DIR}"
rm -rf "$BUILD_DIR" && mkdir -p "$TAR_STAGING"

# Files that go inside the app tar (extracted to $SERVICE_PATH inside container)
cp "$JAR_PATH"                                                        "$TAR_STAGING/${JAR_NAME}"
cp "${PROJECT_DIR}/src/main/resources/run.sh"                         "$TAR_STAGING/run.sh"
cp "${PROJECT_DIR}/src/main/resources/application.properties"         "$TAR_STAGING/application.properties"
chmod +x "$TAR_STAGING/run.sh"

# Create the app tar
tar -cf "${BUILD_DIR}/${APP_NAME}.tar" -C "$TAR_STAGING" .
log "App tar created: ${BUILD_DIR}/${APP_NAME}.tar"

# Copy Dockerfile and required assets into build context
cp "${PROJECT_DIR}/Dockerfile"        "$BUILD_DIR/Dockerfile"

# SkyWalking agent — must exist in project root ./agent/
AGENT_SRC="${PROJECT_DIR}/agent"
if [ -d "$AGENT_SRC" ]; then
    cp -r "$AGENT_SRC" "$BUILD_DIR/agent"
else
    log "WARNING: ./agent/ directory not found — SkyWalking will be skipped inside container"
    mkdir -p "$BUILD_DIR/agent"
fi

# melodyposthook.sh — must exist in project root
MELODY_HOOK="${PROJECT_DIR}/melodyposthook.sh"
if [ -f "$MELODY_HOOK" ]; then
    cp "$MELODY_HOOK" "$BUILD_DIR/melodyposthook.sh"
else
    log "WARNING: melodyposthook.sh not found — creating empty placeholder"
    echo '#!/bin/sh' > "$BUILD_DIR/melodyposthook.sh"
fi

# ── 3. Build & push AMD64 Docker image ────────────────────────────────────────
log "Building AMD64 image: ${IMAGE}"
docker buildx build \
    --platform linux/amd64 \
    --build-arg APP_NAME="${APP_NAME}" \
    --build-arg APP_VERSION="${APP_VERSION}" \
    --tag "${IMAGE}" \
    --push \
    "$BUILD_DIR"

log "Image pushed: ${IMAGE}"

# ── 4. Roll out on Kubernetes ─────────────────────────────────────────────────
log "Rolling out deployment/${DEPLOYMENT} in namespace '${NAMESPACE}'"
kubectl rollout restart deployment/"${DEPLOYMENT}" -n "${NAMESPACE}"

log "Waiting for rollout to complete..."
kubectl rollout status deployment/"${DEPLOYMENT}" -n "${NAMESPACE}" --timeout=300s

log "Done. Pod status:"
kubectl get pods -n "${NAMESPACE}" -l app.kubernetes.io/name=3gpp-mcp

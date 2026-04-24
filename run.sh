#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
REGISTRY="container.visionwaves.com"
IMAGE_NAME="visionwaves/3gpp-mcp"
IMAGE_TAG="${IMAGE_TAG:-V.1.0}"
FULL_IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

NAMESPACE="${NAMESPACE:-ansible}"
DEPLOYMENT="3gpp-mcp-service"

# ── Helper ─────────────────────────────────────────────────────────────────────
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[ERROR] $*" >&2; exit 1; }

# ── 1. Build AMD64 image ───────────────────────────────────────────────────────
log "Building AMD64 image: ${FULL_IMAGE}"
docker buildx build \
  --platform linux/amd64 \
  --tag "${FULL_IMAGE}" \
  --push \
  "$(dirname "$0")"

log "Image pushed successfully: ${FULL_IMAGE}"

# ── 2. Roll out on Kubernetes ──────────────────────────────────────────────────
log "Rolling out deployment/${DEPLOYMENT} in namespace '${NAMESPACE}'"
kubectl rollout restart deployment/"${DEPLOYMENT}" -n "${NAMESPACE}"

log "Waiting for rollout to complete..."
kubectl rollout status deployment/"${DEPLOYMENT}" -n "${NAMESPACE}" --timeout=300s

log "Deployment complete. Pod status:"
kubectl get pods -n "${NAMESPACE}" -l app.kubernetes.io/name=3gpp-mcp

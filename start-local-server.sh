#!/usr/bin/env bash
#
# One-shot local dev startup for nyvra-service: makes sure Docker is running, brings up the
# Compose infra stack (Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak), waits for it to be
# healthy, then runs the app in the foreground.
#
# Usage:
#   ./start-local-server.sh              # infra + app (default)
#   ./start-local-server.sh --infra-only # bring up infra and stop, don't start the app
#
# See docs/PREREQUISITES.md and docs/operations/ENVIRONMENTS.md for background.

set -euo pipefail

# Always run from the repo root, regardless of where this script is invoked from.
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[start-local-server] %s\n' "$1"; }
die() { printf '[start-local-server] ERROR: %s\n' "$1" >&2; exit 1; }

INFRA_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --infra-only) INFRA_ONLY=1 ;;
        *) die "Unknown argument: $arg" ;;
    esac
done

# --- 1. .env -------------------------------------------------------------------------------
if [ ! -f .env ]; then
    log ".env not found — creating from .env.example"
    cp .env.example .env
fi

# --- 2. Docker daemon ------------------------------------------------------------------------
if ! docker info >/dev/null 2>&1; then
    case "$(uname -s)" in
        Darwin)
            log "Docker daemon not running — starting Docker Desktop..."
            open -a Docker 2>/dev/null || die "Could not start Docker Desktop automatically. Start it manually and re-run."
            ;;
        *)
            die "Docker daemon not running. Start it (e.g. 'sudo systemctl start docker') and re-run."
            ;;
    esac

    log "Waiting for the Docker daemon..."
    for _ in $(seq 1 60); do
        docker info >/dev/null 2>&1 && break
        sleep 2
    done
    docker info >/dev/null 2>&1 || die "Docker daemon still not reachable after 120s. Check Docker Desktop and re-run."
fi
log "Docker daemon is up."

# --- 3. Compose stack --------------------------------------------------------------------------
log "Starting the compose stack (postgres, redis, rabbitmq, minio, keycloak)..."
docker compose up -d

# Poll each service with a Compose healthcheck individually rather than 'docker compose up --wait':
# minio-setup is a one-shot init container that's supposed to exit 0, and --wait has no way to
# distinguish that from a crash, so it always times out on this stack. keycloak has no Compose-level
# healthcheck at all (handled separately below).
HEALTHCHECKED_SERVICES="postgres redis rabbitmq minio keycloak-db"
for svc in $HEALTHCHECKED_SERVICES; do
    cid=$(docker compose ps -q "$svc")
    [ -n "$cid" ] || die "Service '$svc' isn't running. Run 'docker compose ps' / 'docker compose logs $svc' to inspect."

    log "Waiting for $svc to be healthy..."
    healthy=0
    for _ in $(seq 1 60); do
        status=$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo "unknown")
        [ "$status" = "healthy" ] && { healthy=1; break; }
        sleep 2
    done
    [ "$healthy" -eq 1 ] || die "$svc didn't become healthy in time. Run 'docker compose logs $svc' to inspect."
done

# Keycloak has no Compose-level healthcheck, and its Quarkus health endpoint (/health/ready) is
# served on the internal management port (9000), which the compose file doesn't publish to the
# host. Instead, poll the 'nyvra' realm's OIDC discovery document on the public port (8081): a 200
# there proves both that Keycloak is serving requests AND that --import-realm finished successfully
# — the one thing we actually care about (can we get a token?).
log "Waiting for Keycloak to finish starting (realm import)..."
KEYCLOAK_READY=0
for _ in $(seq 1 60); do
    if curl -sf http://localhost:8081/realms/nyvra/.well-known/openid-configuration >/dev/null 2>&1; then
        KEYCLOAK_READY=1
        break
    fi
    sleep 2
done
[ "$KEYCLOAK_READY" -eq 1 ] || die "Keycloak didn't finish importing the 'nyvra' realm after 120s. Check 'docker compose logs keycloak'."

log "Infra stack is healthy."
cat <<'EOF'

  Postgres   localhost:5432 (nyvra / nyvra)
  Redis      localhost:6379
  RabbitMQ   http://localhost:15672 (guest / guest)
  MinIO      http://localhost:9001 (nyvra / nyvra-secret)
  Keycloak   http://localhost:8081 (admin / admin)

EOF

if [ "$INFRA_ONLY" -eq 1 ]; then
    log "Infra-only mode — not starting the app. Run './mvnw spring-boot:run' when ready."
    exit 0
fi

# --- 4. App --------------------------------------------------------------------------------
log "Starting the app (profile 'local')..."
exec ./mvnw spring-boot:run

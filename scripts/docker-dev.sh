#!/usr/bin/env bash
# OptiQueue Local Developer Environment Management Script (POSIX / Bash)
# Usage:
#   ./scripts/docker-dev.sh up      - Start Postgres and Redis containers
#   ./scripts/docker-dev.sh down    - Stop containers
#   ./scripts/docker-dev.sh status  - Show container status and ports
#   ./scripts/docker-dev.sh logs    - Follow container logs

set -euo pipefail

ACTION="${1:-up}"

case "$ACTION" in
    up)
        echo "[*] Starting PostgreSQL and Redis containers via Docker Compose..."
        docker compose up -d postgres redis
        echo "[+] Containers started. Waiting for service readiness..."
        sleep 3
        docker compose ps
        ;;
    down)
        echo "[*] Stopping OptiQueue containers..."
        docker compose down
        echo "[+] All containers stopped."
        ;;
    restart)
        echo "[*] Restarting containers..."
        docker compose restart postgres redis
        echo "[+] Containers restarted."
        ;;
    status)
        echo "--- OptiQueue Local Service Status ---"
        docker compose ps
        ;;
    logs)
        echo "[*] Streaming container logs (Ctrl+C to stop)..."
        docker compose logs -f
        ;;
    *)
        echo "Usage: $0 {up|down|restart|status|logs}"
        exit 1
        ;;
esac

# OptiQueue Local Developer Environment Management Script (PowerShell)
# Usage:
#   .\scripts\docker-dev.ps1 up      - Start Postgres and Redis containers
#   .\scripts\docker-dev.ps1 down    - Stop containers
#   .\scripts\docker-dev.ps1 status  - Show container status and ports
#   .\scripts\docker-dev.ps1 logs    - Follow container logs

param (
    [Parameter(Position=0)]
    [ValidateSet("up", "down", "status", "logs", "restart")]
    [string]$Action = "up"
)

$ErrorActionPreference = "Stop"

switch ($Action) {
    "up" {
        Write-Host "[*] Starting PostgreSQL and Redis containers via Docker Compose..." -ForegroundColor Cyan
        docker compose up -d postgres redis
        Write-Host "[+] Containers started. Waiting for database readiness..." -ForegroundColor Green
        Start-Sleep -Seconds 3
        docker compose ps
    }
    "down" {
        Write-Host "[*] Stopping OptiQueue containers..." -ForegroundColor Yellow
        docker compose down
        Write-Host "[+] All containers stopped." -ForegroundColor Green
    }
    "restart" {
        Write-Host "[*] Restarting containers..." -ForegroundColor Cyan
        docker compose restart postgres redis
        Write-Host "[+] Containers restarted." -ForegroundColor Green
    }
    "status" {
        Write-Host "--- OptiQueue Local Service Status ---" -ForegroundColor Cyan
        docker compose ps
    }
    "logs" {
        Write-Host "[*] Streaming container logs (Ctrl+C to stop)..." -ForegroundColor Cyan
        docker compose logs -f
    }
}

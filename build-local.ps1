# build-local.ps1 — Build and run OptiQueue locally using Docker
#
# This script uses Docker to run Maven (no local Maven needed),
# then uses Docker Compose to start the full stack.
#
# Requirements: Docker Desktop running on Windows
# Usage: .\build-local.ps1

Write-Host "=== OptiQueue Local Build & Run ===" -ForegroundColor Cyan

# Step 1: Use Maven inside Docker to build the jar
# We mount the source directory and .m2 cache into the maven container.
# The .m2 mount means downloaded deps are cached on your host — not re-downloaded every run.
Write-Host "`n[1/2] Building application JAR via Docker Maven..." -ForegroundColor Yellow

$m2Cache = "$env:USERPROFILE\.m2"
New-Item -ItemType Directory -Force -Path $m2Cache | Out-Null

docker run --rm `
  -v "${PWD}:/workspace" `
  -v "${m2Cache}:/root/.m2" `
  -w /workspace `
  maven:3.9.6-eclipse-temurin-21 `
  mvn package -DskipTests -B --no-transfer-progress

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERROR] Maven build failed. Check the output above." -ForegroundColor Red
    exit 1
}

Write-Host "`n[2/2] Starting Docker Compose stack (Postgres + Redis + App)..." -ForegroundColor Yellow

# Build the app Docker image and start all services
docker compose up --build -d

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERROR] Docker Compose failed. Check the output above." -ForegroundColor Red
    exit 1
}

Write-Host "`n=== Stack is starting. Waiting for health checks... ===" -ForegroundColor Cyan
Write-Host "Monitor with: docker compose logs -f" -ForegroundColor Gray
Write-Host "Check health: docker compose ps" -ForegroundColor Gray

# Wait for the app to become healthy
$maxWait = 90  # seconds
$waited = 0
Write-Host "`nWaiting up to ${maxWait}s for app to be healthy..." -ForegroundColor Yellow

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 5
    $waited += 5
    $health = docker inspect --format='{{.State.Health.Status}}' optiqueue-app 2>&1
    Write-Host "  [${waited}s] App health: $health"
    if ($health -eq "healthy") {
        Write-Host "`n=== App is UP! ===" -ForegroundColor Green
        Write-Host "API base URL: http://localhost:8080" -ForegroundColor Green
        Write-Host "Health check: curl http://localhost:8080/actuator/health" -ForegroundColor Green
        exit 0
    }
}

Write-Host "`n[WARN] App didn't report healthy within ${maxWait}s." -ForegroundColor Yellow
Write-Host "Check logs: docker compose logs app" -ForegroundColor Yellow

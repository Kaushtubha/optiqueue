#!/usr/bin/env pwsh
# ═══════════════════════════════════════════════════════════════════
# verify.ps1 — OptiQueue local end-to-end verification
#
# Run this after `docker compose up -d` to verify everything works.
# Tests all endpoints and prints a summary.
#
# USAGE (Windows PowerShell):
#   docker compose up -d
#   Start-Sleep 45   # Wait for Spring Boot + Flyway
#   .\scripts\verify.ps1
# ═══════════════════════════════════════════════════════════════════

$BASE = "http://localhost:8080"
$PASS = 0
$FAIL = 0

function Test-Endpoint {
    param($Label, $Expected, $Actual)
    if ($Actual -eq $Expected) {
        Write-Host "  [PASS] $Label" -ForegroundColor Green
        $script:PASS++
    } else {
        Write-Host "  [FAIL] $Label (expected $Expected, got $Actual)" -ForegroundColor Red
        $script:FAIL++
    }
}

Write-Host ""
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  OptiQueue End-to-End Verification" -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# ── 1. HEALTH CHECK ────────────────────────────────────────────
Write-Host "1. Health Check" -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod "$BASE/actuator/health" -ErrorAction Stop
    Test-Endpoint "Actuator health = UP" "UP" $health.status
} catch {
    Write-Host "  [FAIL] Health check failed: $_" -ForegroundColor Red
    $FAIL++
}

# ── 2. SUBMIT JOB ──────────────────────────────────────────────
Write-Host "2. Submit Job (POST /api/jobs)" -ForegroundColor Yellow
$key = "verify-$(Get-Random)"
$body = @{ jobType="EMAIL_SEND"; payload='{"to":"test@example.com"}'; priority=3; idempotencyKey=$key } | ConvertTo-Json
try {
    $resp = Invoke-RestMethod "$BASE/api/jobs" -Method POST -Body $body -ContentType "application/json" -ErrorAction Stop
    Test-Endpoint "Status = PENDING"   "PENDING"     $resp.status
    Test-Endpoint "JobType = EMAIL_SEND" "EMAIL_SEND" $resp.jobType
    Test-Endpoint "Priority = 3"       3             $resp.priority
    $jobId = $resp.id
    Write-Host "  [INFO] Job ID: $jobId" -ForegroundColor Gray
} catch {
    Write-Host "  [FAIL] Submit failed: $_" -ForegroundColor Red
    $FAIL++
    $jobId = $null
}

# ── 3. IDEMPOTENCY ─────────────────────────────────────────────
Write-Host "3. Idempotency (re-submit same key)" -ForegroundColor Yellow
if ($jobId) {
    try {
        $resp2 = Invoke-RestMethod "$BASE/api/jobs" -Method POST -Body $body -ContentType "application/json" -ErrorAction Stop
        Test-Endpoint "Same job ID returned" $jobId $resp2.id
    } catch {
        Write-Host "  [FAIL] Idempotency check failed: $_" -ForegroundColor Red
        $FAIL++
    }
}

# ── 4. GET JOB ─────────────────────────────────────────────────
Write-Host "4. Get Job Status (GET /api/jobs/{id})" -ForegroundColor Yellow
if ($jobId) {
    try {
        $job = Invoke-RestMethod "$BASE/api/jobs/$jobId" -ErrorAction Stop
        Test-Endpoint "Job found" $jobId $job.id
    } catch {
        Write-Host "  [FAIL] Get job failed: $_" -ForegroundColor Red
        $FAIL++
    }
}

# ── 5. LOOKUP BY IDEMPOTENCY KEY ────────────────────────────────
Write-Host "5. Lookup by Idempotency Key" -ForegroundColor Yellow
if ($jobId) {
    try {
        $byKey = Invoke-RestMethod "$BASE/api/jobs?idempotencyKey=$key" -ErrorAction Stop
        Test-Endpoint "Found by key" $jobId $byKey.id
    } catch {
        Write-Host "  [FAIL] Key lookup failed: $_" -ForegroundColor Red
        $FAIL++
    }
}

# ── 6. VALIDATION ──────────────────────────────────────────────
Write-Host "6. Validation (400 on bad input)" -ForegroundColor Yellow
$badBody = @{ jobType=""; priority=99 } | ConvertTo-Json
try {
    Invoke-RestMethod "$BASE/api/jobs" -Method POST -Body $badBody -ContentType "application/json" -ErrorAction Stop
    Write-Host "  [FAIL] Should have returned 400" -ForegroundColor Red
    $FAIL++
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    Test-Endpoint "Validation returns 400" 400 $statusCode
}

# ── 7. CANCEL JOB ──────────────────────────────────────────────
Write-Host "7. Cancel Job (DELETE /api/jobs/{id})" -ForegroundColor Yellow
$cancelBody = @{ jobType="CANCEL_TEST"; priority=5 } | ConvertTo-Json
try {
    $cancelResp = Invoke-RestMethod "$BASE/api/jobs" -Method POST -Body $cancelBody -ContentType "application/json" -ErrorAction Stop
    $cancelId = $cancelResp.id
    $delResp = Invoke-RestMethod "$BASE/api/jobs/$cancelId" -Method DELETE -ErrorAction Stop
    Test-Endpoint "Cancel returns success message" "Job cancelled successfully" $delResp.message
} catch {
    Write-Host "  [FAIL] Cancel failed: $_" -ForegroundColor Red
    $FAIL++
}

# ── 8. DLQ ─────────────────────────────────────────────────────
Write-Host "8. Dead Letter Queue (GET /api/jobs/dead-letter)" -ForegroundColor Yellow
try {
    $dlq = Invoke-RestMethod "$BASE/api/jobs/dead-letter" -ErrorAction Stop
    Test-Endpoint "DLQ endpoint returns array" $true ($dlq -is [System.Object[]] -or $dlq.Count -ge 0)
} catch {
    Write-Host "  [FAIL] DLQ endpoint failed: $_" -ForegroundColor Red
    $FAIL++
}

# ── 9. NOT FOUND ───────────────────────────────────────────────
Write-Host "9. Not Found (GET /api/jobs/00000000-0000-0000-0000-000000000000)" -ForegroundColor Yellow
try {
    Invoke-RestMethod "$BASE/api/jobs/00000000-0000-0000-0000-000000000000" -ErrorAction Stop
    Write-Host "  [FAIL] Should have returned 404" -ForegroundColor Red
    $FAIL++
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    Test-Endpoint "Unknown ID returns 404" 404 $statusCode
}

# ── SUMMARY ────────────────────────────────────────────────────
Write-Host ""
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Results: $PASS passed, $FAIL failed" -ForegroundColor $(if ($FAIL -eq 0) { "Green" } else { "Red" })
Write-Host "══════════════════════════════════════════" -ForegroundColor Cyan

if ($FAIL -eq 0) {
    Write-Host "  ✅ All checks passed — OptiQueue is healthy!" -ForegroundColor Green
} else {
    Write-Host "  ❌ Some checks failed — check docker compose logs app" -ForegroundColor Red
    exit 1
}
Write-Host ""

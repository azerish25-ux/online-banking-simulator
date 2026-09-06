# Starts local Postgres (user-mode cluster, no admin/Docker needed), the Spring
# Boot backend, and the built Next.js frontend. Waits for BOTH services to pass
# health with deadlines, then exits non-zero with an actionable message and the
# relevant log tail if either never comes up. Run .\seed-demo.ps1 afterwards.
#
# F29 hardening: PostgreSQL is DISCOVERED (PGHOME env, PATH, or well-known
# install dirs) instead of assuming one fixed path; the user-mode cluster's
# data directory must exist and match the discovered major version; service
# logs are captured under the cluster directory so failures are diagnosable;
# existing processes on :3000/:8080 are never killed (the health waits just
# time out with a pointer if a stale server occupies the port).
$ErrorActionPreference = "Stop"
$root = Join-Path $env:LOCALAPPDATA "bank-postgres"
$psRoot = $PSScriptRoot
if (-not $psRoot) { $psRoot = (Get-Location).Path }

# --- Discover a compatible PostgreSQL installation -------------------------
function Find-PostgresHome {
  if ($env:PGHOME -and (Test-Path (Join-Path $env:PGHOME "bin\pg_ctl.exe"))) {
    return $env:PGHOME
  }
  $pgConfig = Get-Command pg_config -ErrorAction SilentlyContinue
  if ($pgConfig) {
    $home = Split-Path (Split-Path $pgConfig.Source -Parent) -Parent
    if (Test-Path (Join-Path $home "bin\pg_ctl.exe")) { return $home }
  }
  foreach ($v in @("17", "16", "15", "14", "13")) {
    $candidate = Join-Path "C:\Program Files\PostgreSQL" $v
    if (Test-Path (Join-Path $candidate "bin\pg_ctl.exe")) { return $candidate }
  }
  return $null
}
$pgHome = Find-PostgresHome
if (-not $pgHome) {
  Write-Error "PostgreSQL was not found. Install PostgreSQL 16+, or set PGHOME to its"
  exit 1
}
$pgBin = Join-Path $pgHome "bin"
$pgData = Join-Path $root "data"
if (-not (Test-Path (Join-Path $pgData "PG_VERSION"))) {
  Write-Error "No user-mode cluster at $pgData - initialize one first:"
  exit 1
}
$installedMajor = (Get-Content (Join-Path $pgData "PG_VERSION")).Trim()
$binaryMajor = (Get-Content (Join-Path $pgHome "share\postgresql\PG_VERSION") -ErrorAction SilentlyContinue).Trim()
if ($binaryMajor -and $binaryMajor -ne $installedMajor) {
  Write-Error "PostgreSQL binary at $pgHome is major $binaryMajor but the cluster at $pgData is $installedMajor - use the matching install or point PGHOME at it."
  exit 1
}

# --- Frontend must be built before `npm run start` can serve ----------------
if (-not (Test-Path (Join-Path $psRoot "frontend\.next\BUILD_ID"))) {
  Write-Error "frontend is not built - run 'npm run build' in frontend/ first"
  exit 1
}

# --- Start (or reuse) local Postgres ----------------------------------------
& (Join-Path $pgBin "pg_isready.exe") -h localhost -p 5432 | Out-Null
if ($LASTEXITCODE -ne 0) {
  Start-Process -FilePath (Join-Path $pgBin "pg_ctl.exe") -ArgumentList @("-D", "`"$pgData`"", "-l", "`"$(Join-Path $root 'server.log')`"", "-o", "-p 5432", "start") -WindowStyle Hidden
  Write-Output "Postgres starting (data dir $pgData)..."
  $pgUp = $false
  for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep 1
    & (Join-Path $pgBin "pg_isready.exe") -h localhost -p 5432 | Out-Null
    if ($LASTEXITCODE -eq 0) { $pgUp = $true; break }
  }
  if (-not $pgUp) {
    Write-Error "Postgres never became ready - read $(Join-Path $root 'server.log')"
    exit 1
  }
} else {
  Write-Output "Postgres already running."
}

# --- Start backend + frontend, capturing logs for diagnosis -----------------
$backendLog = Join-Path $root "backend-live.log"
$frontendLog = Join-Path $root "frontend-live.log"
Start-Process powershell -ArgumentList @("-NoExit", "-Command", "Set-Location '$psRoot\backend'; .\mvnw.cmd spring-boot:run 2>&1 | Tee-Object -FilePath '$backendLog'")
Start-Process powershell -ArgumentList @("-NoExit", "-Command", "Set-Location '$psRoot\frontend'; npm run start 2>&1 | Tee-Object -FilePath '$frontendLog'")

# --- Wait for the backend health endpoint with a deadline -------------------
$backendOk = $false
for ($i = 0; $i -lt 36; $i++) {
  try {
    if ((Invoke-RestMethod http://localhost:8080/api/health -TimeoutSec 3).status -eq "UP") { $backendOk = $true; break }
  } catch { }
  Start-Sleep 5
}
if (-not $backendOk) {
  Write-Host "`n--- tail of $backendLog ---" -ForegroundColor Yellow
  if (Test-Path $backendLog) { Get-Content $backendLog -Tail 25 }
  Write-Error "backend never became healthy within 3 minutes. If your own process already occupies :8080, this window will fail to bind - stop it or use that instance. Read $backendLog"
  exit 1
}

# --- Wait for the frontend with a deadline -----------------------------------
$frontendOk = $false
for ($i = 0; $i -lt 12; $i++) {
  try {
    $r = Invoke-WebRequest http://localhost:3000 -UseBasicParsing -TimeoutSec 3
    if ($r.StatusCode -eq 200) { $frontendOk = $true; break }
  } catch { }
  Start-Sleep 5
}
if (-not $frontendOk) {
  Write-Host "`n--- tail of $frontendLog ---" -ForegroundColor Yellow
  if (Test-Path $frontendLog) { Get-Content $frontendLog -Tail 25 }
  Write-Error "frontend never became healthy within 1 minute. If your own process already occupies :3000, this window will fail to bind - stop it or use that instance. Read $frontendLog"
  exit 1
}

Write-Output "Backend -> http://localhost:8080/api/health | Frontend -> http://localhost:3000"
Write-Output "Logs: $backendLog | $frontendLog"
Write-Output "Next: .\seed-demo.ps1"

# Starts local Postgres (user-mode cluster, no admin/Docker needed), the Spring
# Boot backend, and the built Next.js frontend. Blocks until the backend passes
# its health check, then exits non-zero with a pointer to the log if it never
# comes up. Run .\seed-demo.ps1 afterwards for demo data.
$ErrorActionPreference = "Stop"
$root = Join-Path $env:LOCALAPPDATA "bank-postgres"
$pg = "C:\Program Files\PostgreSQL\16\bin"

if (-not (Test-Path "$PSScriptRoot\frontend\.next\BUILD_ID")) {
  Write-Error "frontend is not built - run 'npm run build' in frontend/ first"
  exit 1
}

& "$pg\pg_isready.exe" -h localhost -p 5432 | Out-Null
if ($LASTEXITCODE -ne 0) {
  Start-Process -FilePath "$pg\pg_ctl.exe" -ArgumentList @("-D", "$root\data", "-l", "$root\server.log", "-o", "-p 5432", "start") -WindowStyle Hidden
  Write-Output "Postgres starting..."
} else { Write-Output "Postgres already running." }

Start-Process powershell -ArgumentList @("-NoExit", "-Command", "Set-Location '$PSScriptRoot\backend'; .\mvnw.cmd spring-boot:run")
Start-Process powershell -ArgumentList @("-NoExit", "-Command", "Set-Location '$PSScriptRoot\frontend'; npm run start")

# Block until the backend is actually serving, not just launched.
$ok = $false
for ($i = 0; $i -lt 36; $i++) {
  try {
    if ((Invoke-RestMethod http://localhost:8080/api/health -TimeoutSec 3).status -eq "UP") { $ok = $true; break }
  } catch {}
  Start-Sleep 5
}
if (-not $ok) {
  Write-Error "backend never became healthy within 3 minutes - read backend/backend-live.log"
  exit 1
}
Write-Output "Backend -> http://localhost:8080/api/health | Frontend -> http://localhost:3000"
Write-Output "Next: .\seed-demo.ps1"

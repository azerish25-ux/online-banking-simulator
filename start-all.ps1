# Starts local Postgres (user-mode cluster, no admin/Docker needed) + backend + frontend.
$ErrorActionPreference = "Stop"
$root = Join-Path $env:LOCALAPPDATA "bank-postgres"
& "C:\Program Files\PostgreSQL\16\bin\pg_isready.exe" -h localhost -p 5432 | Out-Null
if ($LASTEXITCODE -ne 0) {
  Start-Process -FilePath "C:\Program Files\PostgreSQL\16\bin\pg_ctl.exe" -ArgumentList "-D `"$root\data`" -l `"$root\server.log`" -o `"-p 5432`" start" -WindowStyle Hidden
  Write-Output "Postgres starting..."
} else { Write-Output "Postgres already running." }
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$PSScriptRoot\backend'; .\mvnw.cmd spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$PSScriptRoot\frontend'; npm run dev"
Write-Output "Backend -> http://localhost:8080/api/health | Frontend -> http://localhost:3000"

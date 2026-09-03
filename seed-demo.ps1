# One-command demo data. Fails loudly if the backend is not healthy on :8080
# (run .\start-all.ps1 first), is idempotent, and prints the logins at the end.
# The 12,500.00 transfer crosses the 10,000 review threshold so the admin
# console has a flagged item waiting in the review queue.
$ErrorActionPreference = "Stop"

$healthy = $false
try { $healthy = (Invoke-RestMethod http://localhost:8080/api/health -TimeoutSec 3).status -eq "UP" } catch {}
if (-not $healthy) { Write-Error "backend not healthy on :8080 - run .\start-all.ps1 first"; exit 1 }

$base = "http://localhost:8080/api/v1"
function Post($path, $body, $token) {
  $h = @{}
  if ($token) { $h.Authorization = "Bearer $token" }
  Invoke-RestMethod -Uri "$base$path" -Method Post -ContentType "application/json" -Headers $h -Body ($body | ConvertTo-Json)
}
function EnsureUser($email, $name) { try { return Post "/auth/register" @{ email = $email; password = "secret123"; fullName = $name } } catch { return Post "/auth/login" @{ email = $email; password = "secret123" } } }
$alice = EnsureUser "alice@bank.local" "Alice Anderson"
$bob = EnsureUser "bob@bank.local" "Bob Baker"
$aliceAccs = Invoke-RestMethod -Uri "$base/accounts" -Headers @{ Authorization = "Bearer $($alice.accessToken)" }
$bobAccs = Invoke-RestMethod -Uri "$base/accounts" -Headers @{ Authorization = "Bearer $($bob.accessToken)" }

# Keep the checking account funded above what both transfers need (12,750).
# Converges: once the balance clears the target, reruns stop depositing.
$checking = $aliceAccs[0]
if ([double]$checking.balance -lt 13000) {
  $deposit = 14000 - [double]$checking.balance
  Post "/accounts/$($checking.id)/deposit" @{ amount = $deposit.ToString("0.00") } $alice.accessToken | Out-Null
}

# Small everyday transfer (idempotency key makes replays safe).
Invoke-RestMethod -Uri "$base/transfers" -Method Post -ContentType "application/json" -Headers @{ Authorization = "Bearer $($alice.accessToken)"; "Idempotency-Key" = "seed-alice-bob-1" } -Body (@{ toIban = $bobAccs[0].iban; amount = "250.00"; memo = "Seed transfer" } | ConvertTo-Json) | Out-Null

# Large transfer: crosses the 10,000 review threshold and shows up flagged in
# the admin review queue (same key on rerun, so it posts exactly once).
Invoke-RestMethod -Uri "$base/transfers" -Method Post -ContentType "application/json" -Headers @{ Authorization = "Bearer $($alice.accessToken)"; "Idempotency-Key" = "seed-alice-bob-flagged" } -Body (@{ toIban = $bobAccs[0].iban; amount = "12500.00"; memo = "Tuition wire" } | ConvertTo-Json) | Out-Null

Write-Output "Seeded: alice@bank.local / bob@bank.local (password secret123). Alice sent Bob `$250 + `$12,500 (flagged for review)."
Write-Output "Alice IBAN: $($aliceAccs[0].iban) | Bob IBAN: $($bobAccs[0].iban)"
Write-Output ""
Write-Output "Customer logins: alice@bank.local | bob@bank.local  (secret123)"
Write-Output "Operator login:  admin@bank.local (change-me-admin-123) -> Operations"

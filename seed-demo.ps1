# One-command demo data (backend must be running on :8080).
$ErrorActionPreference = "Stop"
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
Post "/accounts/$($aliceAccs[0].id)/deposit" @{ amount = "1000.00" } $alice.accessToken | Out-Null
Invoke-RestMethod -Uri "$base/transfers" -Method Post -ContentType "application/json" -Headers @{ Authorization = "Bearer $($alice.accessToken)"; "Idempotency-Key" = "seed-alice-bob-1" } -Body (@{ toIban = $bobAccs[0].iban; amount = "250.00"; memo = "Seed transfer" } | ConvertTo-Json) | Out-Null
Write-Output "Seeded: alice@bank.local / bob@bank.local (password secret123). Alice sent Bob `$250."
Write-Output "Alice IBAN: $($aliceAccs[0].iban) | Bob IBAN: $($bobAccs[0].iban)"

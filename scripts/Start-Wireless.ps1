param([switch]$AllowFirewall)
$ErrorActionPreference = 'Stop'
# This helper changes no repository history and does not delete backend volumes.
$addresses = Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
    $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and
    $_.InterfaceAlias -notmatch 'vEthernet|WSL|Docker'
}
foreach ($address in $addresses) {
    Write-Host "Phone server address: http://$($address.IPAddress):8080/"
}
if ($AllowFirewall) {
    $administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $administrator) { throw 'Run PowerShell as administrator to use -AllowFirewall.' }
    if (-not (Get-NetFirewallRule -DisplayName 'ArenaOne API (local subnet)' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName 'ArenaOne API (local subnet)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -RemoteAddress LocalSubnet -Profile Private,Public | Out-Null
    }
    Write-Host 'TCP 8080 is allowed for devices on the local subnet.'
}
try {
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/health' -TimeoutSec 5
    Write-Host "Backend: $($health.status)"
} catch {
    Write-Host 'Backend is unavailable. Start Docker Desktop and run docker compose up -d --build with your existing DAY1_SALT environment variable.'
}
Write-Host 'Connect the phone and laptop to the same Wi-Fi or phone hotspot. Enter the phone server address in app Settings, then Save and test connection. USB is unnecessary.'

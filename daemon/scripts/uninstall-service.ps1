# Stops and removes the HFM daemon Windows service installed by install-service.ps1.
#   powershell -ExecutionPolicy Bypass -File scripts\uninstall-service.ps1 [-ServiceId hfm-daemon]
param([string]$ServiceId = 'hfm-daemon')
$ErrorActionPreference = 'Stop'

$exe = Join-Path (Split-Path $PSScriptRoot -Parent) "service\$ServiceId.exe"
if (-not (Test-Path $exe)) { throw "Not found: $exe" }
if (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue) {
    & $exe stop
    & $exe uninstall
    if ($LASTEXITCODE -ne 0) { throw "Uninstall failed (exit $LASTEXITCODE)" }
    Write-Host "Removed service '$ServiceId'."
} else {
    Write-Host "Service '$ServiceId' is not installed."
}

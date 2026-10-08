# Starts the daemon with the Oracle backend on an EPM server, in this console.
# To run it as a Windows service instead, see install-service.ps1.
#   powershell -ExecutionPolicy Bypass -File scripts\run-daemon.ps1 [-Config config\daemon.properties]
param([string]$Config = '')
. "$PSScriptRoot\epm-env.ps1"

$cmd = Get-DaemonCommand $Config
& $cmd.Java @($cmd.Arguments)

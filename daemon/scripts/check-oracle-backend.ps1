# Checks the prebuilt Oracle backend (target\hfm-daemon-oracle.jar) against this server's EPM jars.
# Exit code 0 = it matches (or it was compiled here), 1 = rebuild it with build-oracle-backend.ps1.
#   powershell -ExecutionPolicy Bypass -File scripts\check-oracle-backend.ps1
. "$PSScriptRoot\epm-env.ps1"

$cp = @(
    (Join-Path $DaemonRoot 'target\hfm-daemon.jar'),
    (Join-Path $DaemonRoot 'target\hfm-daemon-oracle.jar'),
    $EpmClasspath
) -join ';'
& $Java -cp $cp com.hfmmcp.daemon.backend.oracle.ApiCheck
exit $LASTEXITCODE

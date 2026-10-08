# Compiles the Oracle backend against the EPM jars on this server.
# Run after copying target\hfm-daemon.jar here (or building it with 'mvn package').
#   powershell -ExecutionPolicy Bypass -File scripts\build-oracle-backend.ps1
. "$PSScriptRoot\epm-env.ps1"

$daemonJar = Join-Path $DaemonRoot 'target\hfm-daemon.jar'
if (-not (Test-Path $daemonJar)) { throw "Missing $daemonJar - build it with 'mvn package' first" }

$out = Join-Path $DaemonRoot 'target\oracle-classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null

$sources = Get-ChildItem (Join-Path $DaemonRoot 'src\oracle\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $Javac -source 8 -target 8 -encoding UTF-8 -cp "$daemonJar;$EpmClasspath" -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$oracleJar = Join-Path $DaemonRoot 'target\hfm-daemon-oracle.jar'
& $Jar cf $oracleJar -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $oracleJar"

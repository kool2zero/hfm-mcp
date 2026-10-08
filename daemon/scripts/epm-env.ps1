# Shared EPM paths for the build and run scripts. Dot-source it: . "$PSScriptRoot\epm-env.ps1"
#
# Override any of these with environment variables before running the scripts:
#   EPM_ORACLE_HOME      e.g. E:\Oracle\Middleware\EPMSystem11R1
#   EPM_ORACLE_INSTANCE  e.g. E:\Oracle\Middleware\user_projects\epmsystem1
#   HFM_JAVA_HOME        EPM's bundled JDK 8, e.g. E:\Oracle\Middleware\jdk
#   HFM_EXTRA_CLASSPATH  extra jars or dir\* entries, ';'-separated

$ErrorActionPreference = 'Stop'

if (-not $env:EPM_ORACLE_HOME) { $env:EPM_ORACLE_HOME = 'E:\Oracle\Middleware\EPMSystem11R1' }
if (-not (Test-Path $env:EPM_ORACLE_HOME)) { throw "EPM_ORACLE_HOME not found: $env:EPM_ORACLE_HOME" }
$MiddlewareHome = Split-Path $env:EPM_ORACLE_HOME -Parent

if (-not $env:EPM_ORACLE_INSTANCE) { $env:EPM_ORACLE_INSTANCE = Join-Path $MiddlewareHome 'user_projects\epmsystem1' }
if (-not (Test-Path $env:EPM_ORACLE_INSTANCE)) { throw "EPM_ORACLE_INSTANCE not found: $env:EPM_ORACLE_INSTANCE" }

# EPM's JDK 8: Middleware\jdk (11.2 default), Middleware\jdk1.8*, or a jdk folder next to Middleware.
# A candidate counts when its "release" file says JAVA_VERSION="1.8...".
function Find-EpmJdk([string]$middlewareHome) {
    $candidates = New-Object Collections.Generic.List[string]
    foreach ($base in @($middlewareHome, (Split-Path $middlewareHome -Parent))) {
        if (-not $base -or -not (Test-Path $base)) { continue }
        $candidates.Add((Join-Path $base 'jdk'))
        Get-ChildItem $base -Directory -Filter 'jdk1.8*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) }
    }
    $seen = @()
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if (-not (Test-Path $release)) { continue }
        $m = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
        $ver = '?'
        if ($m) { $ver = $m.Matches[0].Groups[1].Value }
        $seen += "$c ($ver)"
        if ($ver -like '1.8*') { return $c }
    }
    $found = 'none'
    if ($seen.Count -gt 0) { $found = $seen -join ', ' }
    throw "No JDK 8 found near $middlewareHome (checked jdk, jdk1.8*; found: $found). Set HFM_JAVA_HOME to EPM's JDK 8."
}

if (-not $env:HFM_JAVA_HOME) { $env:HFM_JAVA_HOME = Find-EpmJdk $MiddlewareHome }
$Java = Join-Path $env:HFM_JAVA_HOME 'bin\java.exe'
$Javac = Join-Path $env:HFM_JAVA_HOME 'bin\javac.exe'
$Jar = Join-Path $env:HFM_JAVA_HOME 'bin\jar.exe'

# Directories that hold the HFM Java API and what it needs: HFM object model + Thrift transport,
# Shared Services (CSS), EPM registry, and Oracle logging (ODL/DMS). Jar layout differs a little
# between patch sets; if the daemon fails with ClassNotFoundException, find the jar with
#   Get-ChildItem $env:EPM_ORACLE_HOME, $MiddlewareHome\oracle_common -Recurse -Filter *.jar |
#     Where-Object { (& $Jar tf $_.FullName) -match 'the/missing/ClassName' }
# and add its folder to HFM_EXTRA_CLASSPATH.
$EpmClasspathDirs = @(
    "$env:EPM_ORACLE_HOME\common\hfm\11.1.2.0\lib",
    "$env:EPM_ORACLE_HOME\common\jlib\11.1.2.0",
    "$env:EPM_ORACLE_HOME\common\css\11.1.2.0\lib",
    "$env:EPM_ORACLE_HOME\common\loggers\Log4j\1.2.14\lib",
    "$MiddlewareHome\oracle_common\modules\oracle.odl",
    "$MiddlewareHome\oracle_common\modules\oracle.dms",
    "$MiddlewareHome\oracle_common\modules"
) | Where-Object { Test-Path $_ }

$EpmClasspath = ($EpmClasspathDirs | ForEach-Object { "$_\*" }) -join ';'
if ($env:HFM_EXTRA_CLASSPATH) { $EpmClasspath = "$env:HFM_EXTRA_CLASSPATH;$EpmClasspath" }

$DaemonRoot = Split-Path $PSScriptRoot -Parent

# The java.exe and arguments that start the daemon with the Oracle backend. Used by
# run-daemon.ps1 and install-service.ps1 so both start it the same way.
# Extra JVM options (default -Xmx1g) can be set in HFM_DAEMON_JAVA_OPTS.
function Get-DaemonCommand([string]$Config) {
    if (-not $Config) { $Config = Join-Path $DaemonRoot 'config\daemon.properties' }
    if (-not (Test-Path $Config)) { throw "Config not found: $Config (copy config\daemon.example.properties)" }
    $Config = (Resolve-Path $Config).Path
    $jars = @((Join-Path $DaemonRoot 'target\hfm-daemon.jar'), (Join-Path $DaemonRoot 'target\hfm-daemon-oracle.jar'))
    foreach ($jar in $jars) {
        if (-not (Test-Path $jar)) { throw "Missing $jar - build it first (mvn package, then scripts\build-oracle-backend.ps1)" }
    }
    $javaOpts = @('-Xmx1g')
    if ($env:HFM_DAEMON_JAVA_OPTS) { $javaOpts = @($env:HFM_DAEMON_JAVA_OPTS.Trim() -split '\s+') }
    $arguments = @("-DEPM_ORACLE_HOME=$env:EPM_ORACLE_HOME", "-DEPM_ORACLE_INSTANCE=$env:EPM_ORACLE_INSTANCE") +
        $javaOpts + @('-cp', ((@($jars) + $EpmClasspath) -join ';'), 'com.hfmmcp.daemon.DaemonMain', $Config)
    return [pscustomobject]@{ Java = $Java; Arguments = $arguments; Config = $Config }
}

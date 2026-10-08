<#
.SYNOPSIS
Installs or upgrades the HFM MCP server (the HFM daemon) on an EPM 11.2 server, interactively.

.DESCRIPTION
Walks through the whole server setup:
  1. finds EPM (EPM_ORACLE_HOME, the instance, EPM's JDK 8)
  2. downloads the server package from GitHub (or uses a local one) and checks its SHA-256
  3. stops the service if this is an upgrade
  4. installs the files, keeping an existing configuration
  5. checks the prebuilt Oracle backend against this server's EPM jars (compiles it here only if
     they differ, or with -CompileOnServer)
  6. writes config\daemon.properties: application, network access with a TLS certificate,
     API key, optional actions
  7. restricts the config and keystore to Administrators and the service account
  8. optionally opens the port in Windows Firewall
  9. installs and starts the Windows service (WinSW)
 10. checks the daemon answers, and prints what users need for their MCP client

Re-run it to upgrade: files are replaced, the configuration is kept unless you choose otherwise.

Run from an elevated Windows PowerShell on the EPM server. Options may be given as parameters,
or as HFM_MCP_* environment variables when piping into Invoke-Expression:

  # Private repository (token needs read access to the repository's contents):
  $env:HFM_MCP_GITHUB_TOKEN = '<token>'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  irm 'https://api.github.com/repos/kool2zero/hfm-mcp/contents/install.ps1' -Headers @{
      Authorization = "Bearer $env:HFM_MCP_GITHUB_TOKEN"; Accept = 'application/vnd.github.raw' } | iex

  # Public repository:
  irm https://github.com/kool2zero/hfm-mcp/releases/latest/download/install.ps1 | iex

  # Offline (copy install.ps1 and the zip from a release to the server):
  .\install.ps1 -Package .\hfm-mcp-server-<version>.zip

.PARAMETER InstallDir
Where to install. Default: the folder of the existing hfm-daemon service, else <EPM drive>:\hfm-mcp.
Env: HFM_MCP_INSTALL_DIR
.PARAMETER Version
Release to install, e.g. v0.2.0. Default: the latest.  Env: HFM_MCP_VERSION
.PARAMETER Package
Local server package zip instead of downloading.  Env: HFM_MCP_PACKAGE
.PARAMETER GitHubToken
Token for a private repository.  Env: HFM_MCP_GITHUB_TOKEN
.PARAMETER Application
HFM application name (default application for logins).  Env: HFM_MCP_APPLICATION
.PARAMETER CompileOnServer
Compile the Oracle backend against this server's EPM jars instead of using the prebuilt one.
Env: HFM_MCP_COMPILE_ON_SERVER=1
.PARAMETER Unattended
Use parameters/defaults instead of prompting.  Env: HFM_MCP_UNATTENDED=1
#>
param(
    [string]$InstallDir = $env:HFM_MCP_INSTALL_DIR,
    [string]$Version = $env:HFM_MCP_VERSION,
    [string]$Package = $env:HFM_MCP_PACKAGE,
    [string]$GitHubToken = $env:HFM_MCP_GITHUB_TOKEN,
    [string]$Repository = $(if ($env:HFM_MCP_REPOSITORY) { $env:HFM_MCP_REPOSITORY } else { 'kool2zero/hfm-mcp' }),
    [string]$GitHubApi = $(if ($env:HFM_MCP_GITHUB_API) { $env:HFM_MCP_GITHUB_API } else { 'https://api.github.com' }),
    [string]$EpmOracleHome = $env:EPM_ORACLE_HOME,
    [string]$EpmOracleInstance = $env:EPM_ORACLE_INSTANCE,
    [string]$Application = $env:HFM_MCP_APPLICATION,
    [string]$ServiceId = 'hfm-daemon',
    [switch]$CompileOnServer = ($env:HFM_MCP_COMPILE_ON_SERVER -eq '1'),
    [switch]$Unattended = ($env:HFM_MCP_UNATTENDED -eq '1')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

# ================================================================================ helpers

function Write-Step([string]$text) {
    Write-Host ''
    Write-Host "==> $text" -ForegroundColor Cyan
}

function Write-Note([string]$text) { Write-Host "    $text" }

function Read-Value([string]$prompt, [string]$default = '', [switch]$Required) {
    if ($script:Unattended) {
        if ($Required -and -not $default) { throw "Unattended: no value for '$prompt'." }
        return $default
    }
    while ($true) {
        $suffix = ''
        if ($default) { $suffix = " [$default]" }
        $answer = Read-Host "    $prompt$suffix"
        if (-not $answer) { $answer = $default }
        if ($answer -or -not $Required) { return $answer.Trim() }
        Write-Host '    A value is required.' -ForegroundColor Yellow
    }
}

function Read-YesNo([string]$prompt, [bool]$default) {
    if ($script:Unattended) { return $default }
    $hint = 'y/N'
    if ($default) { $hint = 'Y/n' }
    while ($true) {
        $answer = Read-Host "    $prompt [$hint]"
        if (-not $answer) { return $default }
        switch -Regex ($answer.Trim()) {
            '^(y|yes)$' { return $true }
            '^(n|no)$' { return $false }
        }
    }
}

function Read-Choice([string]$prompt, [string[]]$options, [int]$default = 1) {
    if ($script:Unattended) { return $default }
    for ($i = 0; $i -lt $options.Length; $i++) { Write-Note ("  {0}) {1}" -f ($i + 1), $options[$i]) }
    while ($true) {
        $answer = Read-Host "    $prompt [$default]"
        if (-not $answer) { return $default }
        $n = 0
        if ([int]::TryParse($answer, [ref]$n) -and $n -ge 1 -and $n -le $options.Length) { return $n }
    }
}

function New-RandomSecret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return ([Convert]::ToBase64String($buffer)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Test-IsWindows {
    return [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
}

function Test-IsAdministrator {
    if (-not (Test-IsWindows)) { return $false }
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Java .properties: backslashes must be escaped.
function ConvertTo-PropertyValue([string]$value) { return $value.Replace('\', '\\') }

# Sets key=value in a .properties text: replaces "key=..." (also a commented "#key=...") or appends.
function Set-PropertyText([string]$text, [string]$key, [string]$value) {
    $line = "$key=$(ConvertTo-PropertyValue $value)"
    $pattern = '(?m)^[ \t]*#?[ \t]*' + [regex]::Escape($key) + '[ \t]*=.*$'
    $regex = New-Object Text.RegularExpressions.Regex($pattern)
    if ($regex.IsMatch($text)) {
        return $regex.Replace($text, $line.Replace('$', '$$'), 1)
    }
    return $text.TrimEnd() + "`r`n" + $line + "`r`n"
}

function Get-PropertyValue([string]$text, [string]$key) {
    $m = [regex]::Match($text, '(?m)^[ \t]*' + [regex]::Escape($key) + '[ \t]*=[ \t]*(.*?)[ \t]*$')
    if ($m.Success) { return $m.Groups[1].Value.Replace('\\', '\') }
    return ''
}

function Get-Sha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant()
}

# ================================================================================ steps

function Find-EpmOracleHome([string]$given) {
    if ($given -and (Test-Path (Join-Path $given 'common'))) { return (Resolve-Path $given).Path }
    $candidates = New-Object Collections.Generic.List[string]
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
        foreach ($rel in 'Oracle\Middleware\EPMSystem11R1', 'Middleware\EPMSystem11R1', 'EPMSystem11R1') {
            $p = Join-Path $drive.Root $rel
            if (Test-Path (Join-Path $p 'common')) { $candidates.Add($p) }
        }
    }
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return ''
}

function Find-EpmInstance([string]$epmHome, [string]$given) {
    if ($given -and (Test-Path $given)) { return (Resolve-Path $given).Path }
    $projects = Join-Path (Split-Path $epmHome -Parent) 'user_projects'
    if (Test-Path $projects) {
        foreach ($d in (Get-ChildItem $projects -Directory | Sort-Object Name)) {
            if ($d.Name -like 'epmsystem*') { return $d.FullName }
        }
    }
    return ''
}

function Invoke-GitHubApi([string]$url, [string]$token, [string]$outFile = '', [string]$accept = 'application/vnd.github+json') {
    $headers = @{ Accept = $accept; 'User-Agent' = 'hfm-mcp-installer'; 'X-GitHub-Api-Version' = '2022-11-28' }
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($outFile) {
        Invoke-WebRequest -Uri $url -Headers $headers -OutFile $outFile -UseBasicParsing | Out-Null
        return
    }
    return Invoke-RestMethod -Uri $url -Headers $headers -UseBasicParsing
}

# Downloads the server package of a release and checks it against the release's SHA256SUMS.txt.
function Get-ReleasePackage([string]$repo, [string]$version, [string]$token, [string]$workDir, [string]$apiBase = 'https://api.github.com') {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $api = "$apiBase/repos/$repo/releases/latest"
    if ($version) { $api = "$apiBase/repos/$repo/releases/tags/$version" }
    try {
        $release = Invoke-GitHubApi $api $token
    } catch {
        if (-not $token) {
            throw "Cannot read releases of $repo. If the repository is private, set HFM_MCP_GITHUB_TOKEN (or -GitHubToken) to a token with read access. ($($_.Exception.Message))"
        }
        throw
    }
    $zipAsset = $release.assets | Where-Object { $_.name -like 'hfm-mcp-server-*.zip' } | Select-Object -First 1
    $sumAsset = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
    if (-not $zipAsset -or -not $sumAsset) { throw "Release $($release.tag_name) has no hfm-mcp-server zip or SHA256SUMS.txt." }

    $zip = Join-Path $workDir $zipAsset.name
    $sums = Join-Path $workDir 'SHA256SUMS.txt'
    Write-Note "Downloading $($zipAsset.name) ($($release.tag_name))"
    $null = Invoke-GitHubApi $zipAsset.url $token $zip 'application/octet-stream'
    $null = Invoke-GitHubApi $sumAsset.url $token $sums 'application/octet-stream'

    $expected = ''
    foreach ($line in (Get-Content $sums)) {
        $parts = $line.Trim() -split '\s+', 2
        if ($parts.Length -eq 2 -and $parts[1].TrimStart('*') -eq $zipAsset.name) { $expected = $parts[0].ToUpperInvariant() }
    }
    if (-not $expected) { throw "SHA256SUMS.txt has no entry for $($zipAsset.name)." }
    $actual = Get-Sha256 $zip
    if ($actual -ne $expected) { throw "Checksum mismatch for $($zipAsset.name): got $actual, expected $expected." }
    Write-Note "SHA-256 verified."
    return $zip
}

# Copies the package into the install folder, keeping config\daemon.properties, keystore, logs, service.
# The folder an earlier install used, from the service it registered (<dir>\service\<id>.exe).
function Get-ExistingInstallDir([string]$serviceId) {
    try {
        $svc = Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceId'" -ErrorAction Stop
    } catch {
        return $null
    }
    if (-not $svc -or -not $svc.PathName) { return $null }
    $exe = $svc.PathName.Trim()
    if ($exe.StartsWith('"')) { $exe = $exe.Substring(1, $exe.IndexOf('"', 1) - 1) } else { $exe = ($exe -split ' ')[0] }
    $serviceDir = Split-Path -Parent $exe
    if ((Split-Path -Leaf $serviceDir) -ne 'service') { return $null }
    $dir = Split-Path -Parent $serviceDir
    if (Test-Path (Join-Path $dir 'config')) { return $dir }
    return $null
}

function Install-PackageFiles([string]$zip, [string]$dir, [string]$workDir) {
    $staging = Join-Path $workDir 'package'
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force
    if (-not (Test-Path (Join-Path $staging 'target\hfm-daemon.jar'))) {
        throw "$zip is not an hfm-mcp server package (no target\hfm-daemon.jar)."
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($sub in 'target', 'scripts', 'src') {
        $dest = Join-Path $dir $sub
        if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
        Copy-Item (Join-Path $staging $sub) $dest -Recurse
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'config') | Out-Null
    Copy-Item (Join-Path $staging 'config\daemon.example.properties') (Join-Path $dir 'config') -Force
    Copy-Item (Join-Path $staging 'VERSION') $dir -Force
    return (Get-Content (Join-Path $dir 'VERSION') -TotalCount 1).Trim()
}

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

function Get-EpmJdk([string]$epmHome) {
    if ($env:HFM_JAVA_HOME -and (Test-Path $env:HFM_JAVA_HOME)) { return $env:HFM_JAVA_HOME }
    return Find-EpmJdk (Split-Path $epmHome -Parent)
}

# Self-signed certificate for the daemon, plus its PEM for clients' HFM_CA_BUNDLE.
function New-DaemonCertificate([string]$jdk, [string]$keystore, [string]$pem, [string]$password, [string[]]$dnsNames) {
    $keytool = Join-Path $jdk 'bin\keytool.exe'
    if (-not (Test-Path $keytool)) { $keytool = Join-Path $jdk 'bin/keytool' }
    if (Test-Path $keystore) { Remove-Item $keystore -Force }
    $san = ($dnsNames | ForEach-Object { "dns:$_" }) -join ','
    $env:HFM_MCP_KEYSTORE_PASSWORD = $password   # passed via the environment, not the command line
    try {
        & $keytool -genkeypair -alias hfm-daemon -keyalg RSA -keysize 2048 -validity 825 -storetype PKCS12 `
            -keystore $keystore -storepass:env HFM_MCP_KEYSTORE_PASSWORD -keypass:env HFM_MCP_KEYSTORE_PASSWORD `
            -dname "CN=$($dnsNames[0])" -ext "SAN=$san" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed (exit $LASTEXITCODE)" }
        & $keytool -exportcert -rfc -alias hfm-daemon -keystore $keystore -storepass:env HFM_MCP_KEYSTORE_PASSWORD `
            -file $pem 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert failed (exit $LASTEXITCODE)" }
    } finally {
        Remove-Item Env:\HFM_MCP_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    }
}

# Only Administrators, SYSTEM and (if given) the service account may read the file.
function Protect-SecretFile([string]$path, [string]$account) {
    $grants = @('*S-1-5-32-544:(F)', '*S-1-5-18:(F)')
    if ($account) { $grants += "${account}:(R)" }
    & icacls $path /inheritance:r /grant:r @grants | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls failed on $path (exit $LASTEXITCODE)" }
}

# The daemon's /health answer (status, version), or $null if it does not answer in time.
function Wait-DaemonHealthy([string]$url, [int]$seconds = 90) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $previous = $null
    $isDesktop = $PSVersionTable.PSVersion.Major -lt 6
    if ($isDesktop) {
        # Only for this local health check of our own self-signed certificate.
        $previous = [Net.ServicePointManager]::ServerCertificateValidationCallback
        [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
    }
    try {
        while ((Get-Date) -lt $deadline) {
            try {
                if ($isDesktop) {
                    $r = Invoke-RestMethod -Uri "$url/health" -UseBasicParsing -TimeoutSec 5
                } else {
                    $r = Invoke-RestMethod -Uri "$url/health" -SkipCertificateCheck -TimeoutSec 5
                }
                if ($r.status -eq 'ok') { return $r }
            } catch {
                Start-Sleep -Seconds 3
            }
        }
        return $null
    } finally {
        if ($isDesktop) { [Net.ServicePointManager]::ServerCertificateValidationCallback = $previous }
    }
}

# ================================================================================ main

function Install-HfmMcpServer {
    Write-Host ''
    Write-Host 'HFM MCP server setup' -ForegroundColor Green
    Write-Host '--------------------'

    if (-not (Test-IsWindows)) { throw 'Run this on the Windows EPM server.' }
    if (-not (Test-IsAdministrator)) {
        throw 'Run this from an elevated PowerShell (Run as Administrator): it installs a Windows service.'
    }

    # ---- 1. EPM
    Write-Step '1/10  Locating EPM'
    $epmHome = Find-EpmOracleHome $EpmOracleHome
    $epmHome = Read-Value 'EPM_ORACLE_HOME' $epmHome -Required
    if (-not (Test-Path (Join-Path $epmHome 'common\hfm\11.1.2.0\lib\fm-web-objectmodel.jar'))) {
        throw "No HFM client libraries under $epmHome\common\hfm\11.1.2.0\lib. Run this on an HFM server."
    }
    $epmInstance = Read-Value 'EPM_ORACLE_INSTANCE' (Find-EpmInstance $epmHome $EpmOracleInstance) -Required
    if (-not (Test-Path $epmInstance)) { throw "Not found: $epmInstance" }
    $jdk = Get-EpmJdk $epmHome
    Write-Note "EPM JDK: $jdk"

    $dir = $InstallDir
    if (-not $dir) {
        $dir = Get-ExistingInstallDir $ServiceId
        if ($dir) { Write-Note "Found the existing install in $dir" }
    }
    if (-not $dir) { $dir = Join-Path ([IO.Path]::GetPathRoot($epmHome)) 'hfm-mcp' }
    $dir = Read-Value 'Install folder' $dir -Required
    $configFile = Join-Path $dir 'config\daemon.properties'
    $upgrade = Test-Path $configFile

    $workDir = Join-Path ([IO.Path]::GetTempPath()) ('hfm-mcp-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $workDir | Out-Null
    try {
        # ---- 2. Package
        Write-Step '2/10  Getting the server package'
        if ($Package) {
            if (-not (Test-Path $Package)) { throw "Package not found: $Package" }
            $zip = (Resolve-Path $Package).Path
            Write-Note "Using $zip (SHA-256 $(Get-Sha256 $zip))"
        } else {
            $zip = Get-ReleasePackage $Repository $Version $GitHubToken $workDir $GitHubApi
        }

        # ---- 3. Stop an existing service
        Write-Step '3/10  Checking for a running service'
        $serviceExe = Join-Path $dir "service\$ServiceId.exe"
        $existing = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
        if ($existing -and $existing.Status -ne 'Stopped') {
            Write-Note "Stopping $ServiceId for the upgrade"
            Stop-Service -Name $ServiceId -Force
            $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        } else {
            Write-Note 'No running service.'
        }

        # ---- 4. Files
        Write-Step "4/10  Installing files into $dir"
        $installed = Install-PackageFiles $zip $dir $workDir
        Write-Note "Version $installed"

        # ---- 5. Oracle backend
        Write-Step '5/10  Oracle backend'
        $env:EPM_ORACLE_HOME = $epmHome
        $env:EPM_ORACLE_INSTANCE = $epmInstance
        $env:HFM_JAVA_HOME = $jdk
        $compile = [bool]$CompileOnServer
        if (-not $compile) {
            Write-Note 'Checking the prebuilt backend against this server''s EPM jars'
            & (Join-Path $dir 'scripts\check-oracle-backend.ps1')
            if ($LASTEXITCODE -ne 0) {
                Write-Note 'The prebuilt backend does not match this server''s HFM API; compiling it here instead.'
                $compile = $true
            }
        }
        if ($compile) {
            & (Join-Path $dir 'scripts\build-oracle-backend.ps1')
            if (-not (Test-Path (Join-Path $dir 'target\hfm-daemon-oracle.jar'))) {
                throw 'The Oracle backend did not build; see the javac errors above. Missing jars can be added with HFM_EXTRA_CLASSPATH.'
            }
        }

        # ---- 6. Configuration
        Write-Step '6/10  Configuration'
        $reconfigure = $true
        if ($upgrade) {
            $reconfigure = -not (Read-YesNo "Keep the existing configuration ($configFile)?" $true)
        }
        $text = ''
        $serviceAccount = ''
        $url = ''
        $pem = Join-Path $dir 'config\hfm-daemon-cert.pem'
        if ($reconfigure) {
            $text = [IO.File]::ReadAllText((Join-Path $dir 'config\daemon.example.properties'))
            $text = Set-PropertyText $text 'backend' 'oracle'

            $app = Read-Value 'HFM application name' $Application -Required
            $text = Set-PropertyText $text 'hfm.defaultApplication' $app
            $cluster = Read-Value 'HFM cluster (blank = let HFM choose)' ''
            $text = Set-PropertyText $text 'hfm.cluster' $cluster

            Write-Note 'Where do the MCP clients (users'' machines) run?'
            $mode = Read-Choice 'Choice' @('On other machines: listen on the network with TLS (typical)', 'Only on this server: listen on 127.0.0.1') 1
            $port = Read-Value 'Port' '8765' -Required
            $text = Set-PropertyText $text 'server.port' $port
            $fqdn = [Net.Dns]::GetHostEntry('').HostName
            if ($mode -eq 1) {
                $text = Set-PropertyText $text 'server.host' '0.0.0.0'
                $keystore = Join-Path $dir 'config\hfm-daemon.p12'
                $storePassword = New-RandomSecret 24
                $names = @($fqdn)
                $short = $fqdn.Split('.')[0]
                if ($short -ne $fqdn) { $names += $short }
                Write-Note "Creating a TLS certificate for $($names -join ', ')"
                New-DaemonCertificate $jdk $keystore $pem $storePassword $names
                $text = Set-PropertyText $text 'server.tls.keystore' $keystore.Replace('\', '/')
                $text = Set-PropertyText $text 'server.tls.keystorePassword' $storePassword
                $text = Set-PropertyText $text 'server.tls.keystoreType' 'PKCS12'
                $url = "https://${fqdn}:$port"
            } else {
                $text = Set-PropertyText $text 'server.host' '127.0.0.1'
                $url = "http://127.0.0.1:$port"
            }

            $apiKey = New-RandomSecret 32
            $text = Set-PropertyText $text 'server.apiKey' $apiKey

            Write-Note 'Actions change HFM (process control, consolidation, extracts, copies, loads); every action is previewed and confirmed.'
            if (Read-YesNo 'Enable actions?' $false) {
                $allowed = New-Object Collections.Generic.List[string]
                if (Read-YesNo 'Allow process control (START, PROMOTE, SUBMIT, REJECT)?' $true) { $allowed.AddRange([string[]]@('START', 'PROMOTE', 'SUBMIT', 'REJECT')) }
                if (Read-YesNo 'Allow APPROVE and PUBLISH?' $false) { $allowed.AddRange([string[]]@('APPROVE', 'PUBLISH')) }
                if (Read-YesNo 'Allow consolidate / calculate / translate?' $true) { $allowed.AddRange([string[]]@('CONSOLIDATE', 'CALCULATE', 'TRANSLATE')) }
                if (Read-YesNo 'Allow Extended Analytics extracts (EXTRACT_DATA)?' $false) {
                    $allowed.Add('EXTRACT_DATA')
                    $text = Set-PropertyText $text 'extract.exportDir' (Read-Value 'Folder for extracted files' (Join-Path $dir 'exports') -Required).Replace('\', '/')
                    $text = Set-PropertyText $text 'extract.defaultDsn' (Read-Value 'Default DSN for database extracts (blank = none)' '')
                }
                if (Read-YesNo 'Allow data copies (COPY_DATA)?' $false) { $allowed.Add('COPY_DATA') }
                if (Read-YesNo 'Allow data file loads (LOAD_DATA)?' $false) {
                    $allowed.Add('LOAD_DATA')
                    $loadDir = Read-Value 'Folder load files must be in' (Join-Path $dir 'loads') -Required
                    New-Item -ItemType Directory -Force -Path $loadDir | Out-Null
                    $text = Set-PropertyText $text 'load.allowedDir' $loadDir.Replace('\', '/')
                }
                $text = Set-PropertyText $text 'actions.enabled' 'true'
                $text = Set-PropertyText $text 'actions.allowed' ($allowed -join ',')
            } else {
                $text = Set-PropertyText $text 'actions.enabled' 'false'
            }
            $text = Set-PropertyText $text 'actions.auditLog' (Join-Path $dir 'logs\hfm-actions-audit.log').Replace('\', '/')

            [IO.File]::WriteAllText($configFile, $text, (New-Object Text.UTF8Encoding($false)))
            Write-Note "Wrote $configFile"

            $serviceAccount = Read-Value 'Windows account the service runs as, e.g. DOMAIN\svc_epm (blank = LocalSystem; use the EPM services'' account)' ''
        } else {
            $text = [IO.File]::ReadAllText($configFile)
            $hostSetting = Get-PropertyValue $text 'server.host'
            $port = Get-PropertyValue $text 'server.port'
            if (-not $port) { $port = '8765' }
            if (Get-PropertyValue $text 'server.tls.keystore') {
                $url = "https://$([Net.Dns]::GetHostEntry('').HostName):$port"
            } else {
                if (-not $hostSetting -or $hostSetting -eq '0.0.0.0') { $hostSetting = '127.0.0.1' }
                $url = "http://${hostSetting}:$port"
            }
            Write-Note 'Keeping the existing configuration.'
        }

        # ---- 7. Protect secrets
        Write-Step '7/10  Restricting access to the configuration'
        if ($reconfigure) {
            foreach ($f in @($configFile, (Join-Path $dir 'config\hfm-daemon.p12'))) {
                if (Test-Path $f) { Protect-SecretFile $f $serviceAccount; Write-Note "Restricted $f" }
            }
        } else {
            Write-Note 'Unchanged.'
        }

        # ---- 8. Firewall
        Write-Step '8/10  Firewall'
        if ($url -like 'https://*' -and $reconfigure) {
            if (Read-YesNo "Allow inbound TCP $port in Windows Firewall (domain profile)?" $true) {
                $ruleName = "HFM MCP Daemon ($port)"
                if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
                    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $port `
                        -Action Allow -Profile Domain | Out-Null
                }
                Write-Note "Rule '$ruleName' is in place."
            }
        } else {
            Write-Note 'Nothing to do.'
        }

        # ---- 9. Service
        Write-Step '9/10  Installing the Windows service'
        $serviceArgs = @{ Config = $configFile; ServiceId = $ServiceId; Start = $true }
        if ($serviceAccount -and -not (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue)) {
            Write-Note "WinSW will now ask for the password of $serviceAccount."
            $serviceArgs.Account = $true
        }
        & (Join-Path $dir 'scripts\install-service.ps1') @serviceArgs

        # ---- 10. Health check
        Write-Step '10/10  Checking the daemon'
        $health = Wait-DaemonHealthy $url
        if ($health) {
            $running = $health.PSObject.Properties['version']
            if ($running -and $running.Value -ne '0.0.0-dev' -and $running.Value -ne $installed) {
                Write-Host "    The daemon answering at $url is $($running.Value), not $installed. Restart the hfm-daemon service." -ForegroundColor Yellow
            } else {
                Write-Note "The daemon $installed answers at $url"
            }
        } else {
            Write-Host "    The daemon did not answer at $url within 90 s. See $(Join-Path $dir 'logs')." -ForegroundColor Yellow
        }

        # ---- Summary
        $apiKeyNow = Get-PropertyValue ([IO.File]::ReadAllText($configFile)) 'server.apiKey'
        Write-Host ''
        Write-Host 'Done. Give MCP users these settings:' -ForegroundColor Green
        Write-Note "Server version      = $installed (users should run hfm-mcp ${installed} via install-client.ps1)"
        Write-Note "HFM_DAEMON_URL      = $url"
        Write-Note "HFM_DAEMON_API_KEY  = $apiKeyNow"
        if ($url -like 'https://*') {
            if (Test-Path $pem) {
                $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2($pem)
                $sha = [Security.Cryptography.SHA256]::Create()
                try { $hash = $sha.ComputeHash($cert.RawData) } finally { $sha.Dispose() }
                Write-Note ("Certificate SHA-256 = " + (($hash | ForEach-Object { $_.ToString('X2') }) -join ':'))
                Write-Note '                      (install-client.ps1 shows users this fingerprint to compare)'
            }
            Write-Note "HFM_CA_BUNDLE       = only for manual setups: a copy of $pem"
        }
        Write-Note 'Users install the client with install-client.ps1, which asks for the URL and key above.'
        Write-Host ''
        Write-Note "Service: $serviceExe start|stop|restart|status   (or services.msc)"
        Write-Note "Logs:    $(Join-Path $dir 'logs')"
        Write-Note 'Upgrade: run this installer again.'
    } finally {
        Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not $env:HFM_MCP_INSTALLER_NO_RUN) {
    Install-HfmMcpServer
}

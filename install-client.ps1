<#
.SYNOPSIS
Installs or upgrades the HFM MCP client (hfm-mcp.exe) for the current Windows user, interactively.

.DESCRIPTION
No Python and no administrator rights needed. Walks through:
  1. downloading hfm-mcp.exe from GitHub (or a local zip) and checking its SHA-256
  2. installing it under %LOCALAPPDATA%\hfm-mcp and adding it to your PATH
  3. the HFM server's address, API key and your HFM (LDAP) username
  4. trusting the server's certificate (shows its fingerprint to compare with your administrator's)
  5. storing your HFM password in Windows Credential Manager and testing the connection
  6. adding the HFM server to the MCP clients it finds: Antigravity, Claude Desktop, Claude Code,
     Cursor (others: it prints the JSON to paste)

Re-run it to upgrade or change settings; your previous answers are the defaults.
Remove everything with -Uninstall.

  # Private repository (token needs read access to the repository's contents):
  $env:HFM_MCP_GITHUB_TOKEN = '<token>'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  irm 'https://api.github.com/repos/kool2zero/hfm-mcp/contents/install-client.ps1' -Headers @{
      Authorization = "Bearer $env:HFM_MCP_GITHUB_TOKEN"; Accept = 'application/vnd.github.raw' } | iex

  # Public repository:
  irm https://github.com/kool2zero/hfm-mcp/releases/latest/download/install-client.ps1 | iex

  # Offline:
  .\install-client.ps1 -Package .\hfm-mcp-client-<version>-windows-x64.zip

Options can also be given as environment variables (HFM_MCP_*, HFM_DAEMON_URL, HFM_DAEMON_API_KEY,
HFM_USERNAME, HFM_APPLICATION, HFM_CA_BUNDLE), which is how they reach the script under iex.
#>
param(
    [string]$InstallDir = $(if ($env:HFM_MCP_CLIENT_DIR) { $env:HFM_MCP_CLIENT_DIR } else { Join-Path $env:LOCALAPPDATA 'hfm-mcp' }),
    [string]$Version = $env:HFM_MCP_VERSION,
    [string]$Package = $env:HFM_MCP_PACKAGE,
    [string]$GitHubToken = $env:HFM_MCP_GITHUB_TOKEN,
    [string]$Repository = $(if ($env:HFM_MCP_REPOSITORY) { $env:HFM_MCP_REPOSITORY } else { 'kool2zero/hfm-mcp' }),
    [string]$GitHubApi = $(if ($env:HFM_MCP_GITHUB_API) { $env:HFM_MCP_GITHUB_API } else { 'https://api.github.com' }),
    [string]$DaemonUrl = $env:HFM_DAEMON_URL,
    [string]$ApiKey = $env:HFM_DAEMON_API_KEY,
    [string]$Username = $env:HFM_USERNAME,
    [string]$Application = $env:HFM_APPLICATION,
    [string]$CaBundle = $env:HFM_CA_BUNDLE,
    [switch]$Uninstall = ($env:HFM_MCP_UNINSTALL -eq '1'),
    [switch]$Unattended = ($env:HFM_MCP_UNATTENDED -eq '1')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

# ================================================================================ helpers

function Write-Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }
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

function Get-Sha256([string]$path) { return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant() }

function Invoke-GitHubApi([string]$url, [string]$token, [string]$outFile = '', [string]$accept = 'application/vnd.github+json') {
    $headers = @{ Accept = $accept; 'User-Agent' = 'hfm-mcp-installer'; 'X-GitHub-Api-Version' = '2022-11-28' }
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($outFile) {
        Invoke-WebRequest -Uri $url -Headers $headers -OutFile $outFile -UseBasicParsing | Out-Null
        return
    }
    return Invoke-RestMethod -Uri $url -Headers $headers -UseBasicParsing
}

# Downloads the release asset matching $pattern and checks it against the release's SHA256SUMS.txt.
function Get-ReleaseAsset([string]$repo, [string]$version, [string]$token, [string]$workDir, [string]$apiBase, [string]$pattern) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $api = "$apiBase/repos/$repo/releases/latest"
    if ($version) { $api = "$apiBase/repos/$repo/releases/tags/$version" }
    try {
        $release = Invoke-GitHubApi $api $token
    } catch {
        if (-not $token) {
            throw "Cannot read releases of $repo. If the repository is private, set HFM_MCP_GITHUB_TOKEN to a token with read access. ($($_.Exception.Message))"
        }
        throw
    }
    $asset = $release.assets | Where-Object { $_.name -like $pattern } | Select-Object -First 1
    $sumAsset = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
    if (-not $asset -or -not $sumAsset) { throw "Release $($release.tag_name) has no $pattern or SHA256SUMS.txt." }
    $file = Join-Path $workDir $asset.name
    $sums = Join-Path $workDir 'SHA256SUMS.txt'
    Write-Note "Downloading $($asset.name) ($($release.tag_name))"
    $null = Invoke-GitHubApi $asset.url $token $file 'application/octet-stream'
    $null = Invoke-GitHubApi $sumAsset.url $token $sums 'application/octet-stream'
    $expected = ''
    foreach ($line in (Get-Content $sums)) {
        $parts = $line.Trim() -split '\s+', 2
        if ($parts.Length -eq 2 -and $parts[1].TrimStart('*') -eq $asset.name) { $expected = $parts[0].ToUpperInvariant() }
    }
    if (-not $expected) { throw "SHA256SUMS.txt has no entry for $($asset.name)." }
    $actual = Get-Sha256 $file
    if ($actual -ne $expected) { throw "Checksum mismatch for $($asset.name): got $actual, expected $expected." }
    Write-Note 'SHA-256 verified.'
    return $file
}

# Unpacks the client zip into <dir>\app, swapping folders so a failed copy never leaves a broken install.
function Install-ClientFiles([string]$zip, [string]$dir) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $new = Join-Path $dir 'app.new'
    $app = Join-Path $dir 'app'
    $old = Join-Path $dir 'app.old'
    foreach ($p in @($new, $old)) { if (Test-Path $p) { Remove-Item $p -Recurse -Force } }
    Expand-Archive -LiteralPath $zip -DestinationPath $new -Force
    $exeName = 'hfm-mcp.exe'
    if (-not (Test-Path (Join-Path $new $exeName))) { $exeName = 'hfm-mcp' }
    if (-not (Test-Path (Join-Path $new $exeName))) { throw "$zip is not an hfm-mcp client package." }
    if (Test-Path $app) { Rename-Item $app 'app.old' }
    Rename-Item $new 'app'
    if (Test-Path $old) { Remove-Item $old -Recurse -Force -ErrorAction SilentlyContinue }
    return (Join-Path $app $exeName)
}

function Add-ToUserPath([string]$folder) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    $parts = @()
    if ($current) { $parts = $current.Split(';') | Where-Object { $_ } }
    if ($parts -notcontains $folder) {
        [Environment]::SetEnvironmentVariable('Path', (($parts + $folder) -join ';'), 'User')
        return $true
    }
    return $false
}

function Remove-FromUserPath([string]$folder) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $current) { return }
    $parts = $current.Split(';') | Where-Object { $_ -and $_ -ne $folder }
    [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
}

function Get-CertFingerprint([Security.Cryptography.X509Certificates.X509Certificate2]$cert) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash($cert.RawData) } finally { $sha.Dispose() }
    return (($hash | ForEach-Object { $_.ToString('X2') }) -join ':')
}

function ConvertTo-Pem([Security.Cryptography.X509Certificates.X509Certificate2]$cert) {
    $b64 = [Convert]::ToBase64String($cert.RawData)
    $lines = for ($i = 0; $i -lt $b64.Length; $i += 64) { $b64.Substring($i, [Math]::Min(64, $b64.Length - $i)) }
    return "-----BEGIN CERTIFICATE-----`n" + ($lines -join "`n") + "`n-----END CERTIFICATE-----`n"
}

# Connects to the server and returns the certificates to trust: the whole chain when Windows already
# trusts it (corporate CA), otherwise the server's own certificate (self-signed).
function Get-ServerCertificates([string]$hostName, [int]$port) {
    $tcp = New-Object Net.Sockets.TcpClient
    try {
        $tcp.Connect($hostName, $port)
        $callback = [Net.Security.RemoteCertificateValidationCallback] { param($s, $c, $ch, $e) $true }
        $ssl = New-Object Net.Security.SslStream($tcp.GetStream(), $false, $callback)
        try {
            $ssl.AuthenticateAsClient($hostName)
            $leaf = New-Object Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
        } finally { $ssl.Dispose() }
    } finally { $tcp.Dispose() }
    $chain = New-Object Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode = [Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
    $trusted = $chain.Build($leaf)
    $certs = @($leaf)
    if ($trusted) { $certs = @($chain.ChainElements | ForEach-Object { $_.Certificate }) }
    return [pscustomobject]@{ Leaf = $leaf; Certificates = $certs; TrustedByWindows = $trusted }
}

# Null when the URL is well-formed and its host:port accepts connections, else what is wrong.
function Test-ServerReachable([string]$url) {
    $uri = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$uri) -or ($uri.Scheme -ne 'https' -and $uri.Scheme -ne 'http')) {
        return "'$url' is not a URL like https://server.domain:8765"
    }
    try {
        $null = [Net.Dns]::GetHostAddresses($uri.Host)
    } catch {
        return "Cannot find the server '$($uri.Host)' (check the spelling)."
    }
    $tcp = New-Object Net.Sockets.TcpClient
    try {
        $attempt = $tcp.BeginConnect($uri.Host, $uri.Port, $null, $null)
        if (-not $attempt.AsyncWaitHandle.WaitOne(5000) -or -not $tcp.Connected) {
            return "'$($uri.Host)' does not answer on port $($uri.Port) (is the HFM daemon running, and the port open in the firewall?)"
        }
        $tcp.EndConnect($attempt)
    } catch {
        return "Cannot connect to $($uri.Host):$($uri.Port): $($_.Exception.InnerException.Message)"
    } finally {
        $tcp.Dispose()
    }
    return $null
}

# Sets mcpServers.hfm in an MCP client's JSON config (Antigravity, Claude Desktop, Cursor), keeping
# everything else and a .bak copy of the original.
function Set-McpServerEntry([string]$configPath, [string]$exe, [Collections.IDictionary]$envVars) {
    # Windows PowerShell 5.1 can serialize arrays as {"value":[...],"Count":n} once a module has
    # extended System.Array; drop that extension for this session so the JSON stays plain.
    if (Get-TypeData -TypeName System.Array -ErrorAction SilentlyContinue) { Remove-TypeData -TypeName System.Array }
    $config = New-Object PSObject
    if (Test-Path $configPath) {
        $raw = [IO.File]::ReadAllText($configPath)
        if ($raw.Trim()) {
            $config = $raw | ConvertFrom-Json
        }
        Copy-Item $configPath "$configPath.bak" -Force
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path $configPath -Parent) | Out-Null
    }
    if (-not $config.PSObject.Properties['mcpServers'] -or $null -eq $config.mcpServers) {
        $config | Add-Member -Force -NotePropertyName mcpServers -NotePropertyValue (New-Object PSObject)
    }
    $entry = [ordered]@{ command = $exe; args = @(); env = $envVars }
    $config.mcpServers | Add-Member -Force -NotePropertyName hfm -NotePropertyValue $entry
    $json = $config | ConvertTo-Json -Depth 32
    [IO.File]::WriteAllText($configPath, $json, (New-Object Text.UTF8Encoding($false)))
}

function Remove-McpServerEntry([string]$configPath) {
    if (-not (Test-Path $configPath)) { return $false }
    $config = [IO.File]::ReadAllText($configPath) | ConvertFrom-Json
    if (-not $config.PSObject.Properties['mcpServers'] -or -not $config.mcpServers.PSObject.Properties['hfm']) { return $false }
    Copy-Item $configPath "$configPath.bak" -Force
    $config.mcpServers.PSObject.Properties.Remove('hfm')
    [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 32), (New-Object Text.UTF8Encoding($false)))
    return $true
}

# MCP clients that keep their servers in a JSON file, and whether they look installed.
function Get-McpClientConfigs {
    $home_ = $env:USERPROFILE
    return @(
        [pscustomobject]@{ Name = 'Antigravity'; Path = (Join-Path $home_ '.gemini\antigravity\mcp_config.json'); Present = (Test-Path (Join-Path $home_ '.gemini\antigravity')) },
        [pscustomobject]@{ Name = 'Claude Desktop'; Path = (Join-Path $env:APPDATA 'Claude\claude_desktop_config.json'); Present = (Test-Path (Join-Path $env:APPDATA 'Claude')) },
        [pscustomobject]@{ Name = 'Cursor'; Path = (Join-Path $home_ '.cursor\mcp.json'); Present = (Test-Path (Join-Path $home_ '.cursor')) }
    )
}

function Read-Settings([string]$dir) {
    $file = Join-Path $dir 'settings.json'
    if (Test-Path $file) { return ([IO.File]::ReadAllText($file) | ConvertFrom-Json) }
    return $null
}

function Get-Setting($settings, [string]$name) {
    if ($settings -and $settings.PSObject.Properties[$name]) { return [string]$settings.$name }
    return ''
}

# ================================================================================ uninstall

function Uninstall-HfmMcpClient {
    Write-Step 'Removing the HFM MCP client'
    $exe = Join-Path $InstallDir 'app\hfm-mcp.exe'
    $settings = Read-Settings $InstallDir
    if ((Test-Path $exe) -and $settings) {
        $env:HFM_DAEMON_URL = Get-Setting $settings 'HFM_DAEMON_URL'
        $env:HFM_DAEMON_API_KEY = Get-Setting $settings 'HFM_DAEMON_API_KEY'
        $env:HFM_USERNAME = Get-Setting $settings 'HFM_USERNAME'
        & $exe logout
    }
    foreach ($client in (Get-McpClientConfigs)) {
        if (Remove-McpServerEntry $client.Path) { Write-Note "Removed from $($client.Name)" }
    }
    if (Get-Command claude -ErrorAction SilentlyContinue) {
        & claude mcp remove -s user hfm 2>$null | Out-Null
    }
    Remove-FromUserPath (Join-Path $InstallDir 'app')
    if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
    Write-Note 'Done.'
}

# ================================================================================ install

function Install-HfmMcpClient {
    Write-Host ''
    Write-Host 'HFM MCP client setup' -ForegroundColor Green
    Write-Host '--------------------'
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'This installer is for Windows. On macOS/Linux: uv tool install hfm-mcp (see the README).'
    }
    $previous = Read-Settings $InstallDir

    # ---- 1. Download
    Write-Step '1/6  Getting hfm-mcp.exe'
    $workDir = Join-Path ([IO.Path]::GetTempPath()) ('hfm-mcp-client-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $workDir | Out-Null
    try {
        if ($Package) {
            if (-not (Test-Path $Package)) { throw "Package not found: $Package" }
            $zip = (Resolve-Path $Package).Path
            Write-Note "Using $zip (SHA-256 $(Get-Sha256 $zip))"
        } else {
            $zip = Get-ReleaseAsset $Repository $Version $GitHubToken $workDir $GitHubApi 'hfm-mcp-client-*-windows-x64.zip'
        }

        # ---- 2. Install
        Write-Step "2/6  Installing into $InstallDir"
        $running = @(Get-Process -Name 'hfm-mcp' -ErrorAction SilentlyContinue | Where-Object {
            try { $_.Path -and $_.Path.StartsWith($InstallDir, [StringComparison]::OrdinalIgnoreCase) } catch { $false } })
        if ($running.Count -gt 0) {
            Write-Note "hfm-mcp.exe is running ($($running.Count) process(es)), probably started by an MCP client."
            if (Read-YesNo 'Stop it so it can be upgraded? (the MCP client restarts it when needed)' $true) {
                $running | Stop-Process -Force
                Start-Sleep -Seconds 1
            } else {
                throw 'Close your MCP clients (Antigravity, Claude, Cursor) and run the installer again.'
            }
        }
        $exe = Install-ClientFiles $zip $InstallDir
        Write-Note "Installed $(& $exe --version)"
        if (Add-ToUserPath (Split-Path $exe -Parent)) { Write-Note 'Added to your PATH (new terminals will find hfm-mcp).' }
    } finally {
        Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    # ---- 3. Server and user
    Write-Step '3/6  HFM server (your administrator has these)'
    $url = $DaemonUrl
    if (-not $url) { $url = Get-Setting $previous 'HFM_DAEMON_URL' }
    while ($true) {
        $url = (Read-Value 'Server URL, e.g. https://epm01.corp.example.com:8765' $url -Required).TrimEnd('/')
        $problem = Test-ServerReachable $url
        if (-not $problem) { break }
        Write-Host "    $problem" -ForegroundColor Yellow
        if ($script:Unattended) { throw $problem }
    }
    $uri = [Uri]$url
    $key = $ApiKey
    if (-not $key) { $key = Get-Setting $previous 'HFM_DAEMON_API_KEY' }
    $keyHint = ''
    if ($key) { $keyHint = $key.Substring(0, [Math]::Min(6, $key.Length)) + '...' }
    $enteredKey = Read-Value "API key$(if ($keyHint) { " (Enter keeps $keyHint)" })" '' -Required:(-not $key)
    if ($enteredKey) { $key = $enteredKey }
    $user = $Username
    if (-not $user) { $user = Get-Setting $previous 'HFM_USERNAME' }
    if (-not $user) { $user = $env:USERNAME }
    $user = Read-Value 'Your HFM (LDAP) username' $user -Required
    $app = $Application
    if (-not $app) { $app = Get-Setting $previous 'HFM_APPLICATION' }
    $app = Read-Value 'HFM application (blank = the server''s default)' $app

    # ---- 4. Certificate
    Write-Step '4/6  Server certificate'
    $caFile = ''
    if ($uri.Scheme -eq 'https') {
        $caFile = Join-Path $InstallDir 'hfm-daemon-ca.pem'
        # HFM_CA_BUNDLE from an earlier install points at $caFile itself: check the server again.
        if ($CaBundle -and (Test-Path -LiteralPath $CaBundle) -and
            ((Resolve-Path -LiteralPath $CaBundle).ProviderPath -eq [IO.Path]::GetFullPath($caFile))) {
            $CaBundle = ''
        }
        if ($CaBundle) {
            Copy-Item -LiteralPath $CaBundle -Destination $caFile -Force
            Write-Note "Using the certificate file $CaBundle"
        } else {
            $port = $uri.Port
            $server = Get-ServerCertificates $uri.Host $port
            Write-Note "Server certificate: $($server.Leaf.Subject)"
            Write-Note "  valid until $($server.Leaf.NotAfter.ToString('yyyy-MM-dd'))"
            Write-Note "  SHA-256 fingerprint: $(Get-CertFingerprint $server.Leaf)"
            if ($server.TrustedByWindows) {
                Write-Note '  Issued by a certificate authority this PC already trusts.'
            } else {
                Write-Note '  Self-signed: compare the fingerprint with the one your administrator gave you.'
            }
            $pem = ($server.Certificates | ForEach-Object { ConvertTo-Pem $_ }) -join ''
            $saved = ''
            if (Test-Path -LiteralPath $caFile) {
                try {
                    $saved = (New-Object Security.Cryptography.X509Certificates.X509Certificate2($caFile)).Thumbprint
                } catch { $saved = '' }
            }
            if ($saved -and $saved -eq $server.Leaf.Thumbprint) {
                Write-Note '  Same certificate you trusted last time.'
            } elseif (-not (Read-YesNo 'Trust this certificate for the HFM server?' $server.TrustedByWindows)) {
                throw 'Certificate not trusted. Ask your administrator for the server certificate and re-run with -CaBundle <file>.'
            }
            [IO.File]::WriteAllText($caFile, $pem, (New-Object Text.UTF8Encoding($false)))
        }
        Write-Note "Saved to $caFile"
    } else {
        Write-Note 'Plain HTTP (server on this machine): no certificate needed.'
    }

    # Settings for hfm-mcp.exe: this process, the MCP client configs, and the next run's defaults.
    $envVars = [ordered]@{ HFM_DAEMON_URL = $url; HFM_DAEMON_API_KEY = $key; HFM_USERNAME = $user }
    if ($app) { $envVars.HFM_APPLICATION = $app }
    if ($caFile) { $envVars.HFM_CA_BUNDLE = $caFile }
    $actionsDefault = (Get-Setting $previous 'HFM_ENABLE_ACTIONS') -eq 'true'
    Write-Note ''
    if (Read-YesNo 'Add the action tools (process control, consolidation, ...; the server must allow them)?' $actionsDefault) {
        $envVars.HFM_ENABLE_ACTIONS = 'true'
    }
    foreach ($k in $envVars.Keys) { Set-Item -Path "Env:\$k" -Value $envVars[$k] }
    Remove-Item Env:\HFM_PASSWORD -ErrorAction SilentlyContinue
    $settingsJson = [pscustomobject]$envVars | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $InstallDir 'settings.json'), $settingsJson, (New-Object Text.UTF8Encoding($false)))

    # ---- 5. Password and connection test
    Write-Step '5/6  Your HFM password (kept in Windows Credential Manager)'
    $connected = $false
    if ($script:Unattended) {
        Write-Note "Skipped (unattended). Run 'hfm-mcp login' once in a terminal."
    } else {
        for ($try = 1; $try -le 3 -and -not $connected; $try++) {
            & $exe login
            if ($LASTEXITCODE -eq 0) {
                & $exe check
                $connected = ($LASTEXITCODE -eq 0)
            }
            if (-not $connected -and $try -lt 3 -and -not (Read-YesNo 'Try again?' $true)) { break }
        }
        if (-not $connected) { Write-Host "    Not connected yet. Fix the above, then run 'hfm-mcp login'." -ForegroundColor Yellow }
    }

    # ---- 6. MCP clients
    Write-Step '6/6  Adding the HFM server to your MCP clients'
    $added = @()
    foreach ($client in (Get-McpClientConfigs)) {
        if (-not $client.Present) { continue }
        if (Read-YesNo "Add it to $($client.Name) ($($client.Path))?" $true) {
            try {
                Set-McpServerEntry $client.Path $exe $envVars
                $added += $client.Name
            } catch {
                Write-Host "    Could not update $($client.Path): $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
    }
    if (Get-Command claude -ErrorAction SilentlyContinue) {
        if (Read-YesNo 'Add it to Claude Code (user scope)?' $true) {
            & claude mcp remove -s user hfm 2>$null | Out-Null
            $claudeArgs = @('mcp', 'add', '-s', 'user', 'hfm')
            foreach ($k in $envVars.Keys) { $claudeArgs += @('-e', "$k=$($envVars[$k])") }
            $claudeArgs += @('--', $exe)
            & claude @claudeArgs | Out-Null
            if ($LASTEXITCODE -eq 0) { $added += 'Claude Code' }
        }
    }

    Write-Host ''
    Write-Host 'Done.' -ForegroundColor Green
    if ($added.Count -gt 0) {
        Write-Note "Added to: $($added -join ', '). Restart them to load the HFM tools."
    }
    Write-Note 'For any other MCP client, add this server definition:'
    $snippet = [ordered]@{ mcpServers = [ordered]@{ hfm = [ordered]@{ command = $exe; env = $envVars } } } | ConvertTo-Json -Depth 8
    Write-Host $snippet
    Write-Note 'Change your stored password any time with: hfm-mcp login'
    Write-Note 'Upgrade by running this installer again; remove with -Uninstall.'
}

if (-not $env:HFM_MCP_INSTALLER_NO_RUN) {
    if ($Uninstall) { Uninstall-HfmMcpClient } else { Install-HfmMcpClient }
}

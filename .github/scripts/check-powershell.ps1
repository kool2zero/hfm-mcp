# Parses every PowerShell script users run, so a syntax error (which stops `irm | iex` before
# anything runs) fails CI instead of reaching a release.
$files = @('install.ps1', 'install-client.ps1') + @(Get-ChildItem daemon/scripts/*.ps1 | ForEach-Object { $_.FullName })
$bad = 0
foreach ($f in $files) {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $f).Path, [ref]$null, [ref]$errors)
    foreach ($e in $errors) {
        Write-Host "::error file=$f,line=$($e.Extent.StartLineNumber)::$($e.Message)"
        $bad++
    }
}
if ($bad) { exit 1 }
Write-Host "$($files.Count) PowerShell scripts parse."

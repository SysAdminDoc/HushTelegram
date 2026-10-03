<#
.SYNOPSIS
    Check lease, device identity, signing and downgrade refusal without a real device.
#>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $Root 'scripts/common.ps1')
. (Join-Path $Root 'scripts/device-install.ps1')

$caseRoot = Join-Path $env:TEMP ('hushtelegram-install-test-' + [guid]::NewGuid().ToString('N'))
$cases = 0
New-Item -ItemType Directory -Path $caseRoot | Out-Null
try {
    $leaseDir = Join-Path $caseRoot 'device-leases'
    New-Item -ItemType Directory -Path $leaseDir -Force | Out-Null
    $candidate = Join-Path $caseRoot 'candidate.apk'
    [IO.File]::WriteAllText($candidate, 'synthetic APK')
    $fakeAdb = Join-Path $caseRoot 'adb.cmd'
    $log = Join-Path $caseRoot 'adb.log'
    $modePath = Join-Path $caseRoot 'mode.txt'
    $leasePath = Join-Path $leaseDir 'emulator-7778.json'
    $script:fixtureLeasePath = $leasePath
    $token = [guid]::NewGuid().ToString()
    $chat = 'install-contract-test'
    $fakeBody = @'
@echo off
set /p FAKE_ADB_MODE=<"%~dp0mode.txt"
echo %*>>"%~dp0adb.log"
if "%3"=="get-state" goto state
if "%3"=="get-serialno" echo %2
if "%3|%4|%5"=="shell|getprop|ro.product.model" echo FakeModel
if "%3|%4|%5"=="emu|avd|name" echo FakeAVD
if "%3|%4|%5"=="shell|pm|path" goto package_path
if "%3"=="pull" goto pull
if "%3"=="install" goto install
exit /b 0
:state
if "%FAKE_ADB_MODE%"=="disconnected" exit /b 17
echo device
exit /b 0
:package_path
if "%FAKE_ADB_MODE%"=="check-fail" exit /b 18
if "%FAKE_ADB_MODE%"=="check-text" echo Error: package manager unavailable
if "%FAKE_ADB_MODE%"=="absent" exit /b 0
if not "%FAKE_ADB_MODE%"=="check-text" echo package:/data/app/example/base.apk
exit /b 0
:pull
if "%FAKE_ADB_MODE%"=="pull-fail" exit /b 19
copy /y "%~dp0candidate.apk" "%~5" >nul
exit /b 0
:install
if "%FAKE_ADB_MODE%"=="install-fail" exit /b 20
if "%FAKE_ADB_MODE%"=="false-success" echo Failure [INSTALL_FAILED_TEST]
if not "%FAKE_ADB_MODE%"=="false-success" echo Success
exit /b 0
'@
    [IO.File]::WriteAllText($fakeAdb, $fakeBody, [Text.Encoding]::ASCII)
    # Native tooling is replaced only in this isolated child script. Certificate/version facts
    # vary independently of ADB's answers, so the ordering assertions don't reuse production logic.
    function Get-ApkManifestFacts {
        param([string]$Apk, [string]$Aapt2)
        $installed = (Split-Path -Leaf $Apk) -ceq 'installed.apk'
        $package = if ($script:mode -ceq 'wrong-package' -and -not $installed) { 'com.example.other' } else { 'com.example.app' }
        $version = if ($script:mode -ceq 'downgrade' -and $installed) { '21' } else { '20' }
        return [pscustomobject]@{ package = $package; versionCode = $version; versionName = '1.0' }
    }
    function Get-VendorSignerDigests {
        param([string]$Apk, [string]$Aapt2)
        if ($script:mode -ceq 'lose-lease' -and (Split-Path -Leaf $Apk) -ceq 'installed.apk') {
            Remove-Item -LiteralPath $script:fixtureLeasePath -Force
        }
        if ($script:mode -ceq 'signer-mismatch' -and (Split-Path -Leaf $Apk) -ceq 'installed.apk') { return ('b' * 64) }
        return ('a' * 64)
    }
    function Reset-Case {
        param([string]$Mode)
        $script:mode = $Mode
        [IO.File]::WriteAllText($modePath, $Mode, [Text.Encoding]::ASCII)
        if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }
        $now = [DateTimeOffset]::UtcNow
        $script:lease = [ordered]@{
            schemaVersion = 1; serial = 'emulator-7778'; project = 'HushTelegram'; chatIdentity = $chat;
            ownershipToken = $token; acquiredUtc = $now.AddMinutes(-1).ToString('o'); expiresUtc = $now.AddMinutes(5).ToString('o'); purpose = 'synthetic install checks'
        }
        Write-Lease
    }
    function Write-Lease { [IO.File]::WriteAllText($leasePath, ($script:lease | ConvertTo-Json)) }
    function Run-Install {
        param([string]$Model = 'FakeModel', [string]$Avd = 'FakeAVD')
        Install-AndroidPackage -Adb $fakeAdb -Serial 'emulator-7778' -PackageName 'com.example.app' `
            -Apk $candidate -Aapt2 'unused' -LeaseDirectory $leaseDir -LeaseToken $token -ChatIdentity $chat `
            -ExpectedModel $Model -ExpectedAvd $Avd -WorkDirectory $caseRoot
    }
    function Assert-Refusal {
        param([scriptblock]$Action, [bool]$NoAdb = $false)
        $caught = $false
        try { & $Action } catch { $caught = $true }
        if (-not $caught) { throw 'Unsafe install was accepted.' }
        $calls = if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log) } else { @() }
        if ($NoAdb -and $calls.Count) { throw 'ADB ran before lease ownership was proved.' }
        if (@($calls | Where-Object { $_ -match '\s(install|uninstall)\s' }).Count) { throw 'A refused preflight reached mutation.' }
        $script:cases++
    }
    foreach ($modeValue in @('present', 'absent')) {
        Reset-Case $modeValue
        Run-Install
        $calls = @(Get-Content -LiteralPath $log)
        $installs = @($calls | Where-Object { $_ -match '\sinstall\s' })
        if ($installs.Count -ne 1 -or $installs[0] -notmatch '^-s emulator-7778 install -r ' -or
            @($calls | Where-Object { $_ -match '\s(-g|-d|uninstall)\b' }).Count) { throw 'Install granted permissions, downgraded or uninstalled.' }
        $pathIndex = [Array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($line) $line -match ' shell pm path ' })
        $installIndex = [Array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($line) $line -match ' install -r ' })
        if ($pathIndex -lt 0 -or $installIndex -le $pathIndex) { throw 'Install preceded package preflight.' }
        if (@(Get-ChildItem -LiteralPath $caseRoot -Directory -Filter 'install-*').Count) { throw 'Owned install scratch was not removed.' }
        $cases++
    }
    foreach ($field in @('ownershipToken', 'project', 'chatIdentity', 'serial', 'schemaVersion')) {
        Reset-Case 'present'
        $script:lease[$field] = 'wrong'
        Write-Lease
        Assert-Refusal { Run-Install } -NoAdb $true
    }
    Reset-Case 'present'
    $script:lease.expiresUtc = [DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o')
    Write-Lease
    Assert-Refusal { Run-Install } -NoAdb $true
    Reset-Case 'present'
    $script:lease.acquiredUtc = [DateTimeOffset]::UtcNow.AddMinutes(1).ToString('o')
    Write-Lease
    Assert-Refusal { Run-Install } -NoAdb $true
    Reset-Case 'present'
    Remove-Item -LiteralPath $leasePath -Force
    Assert-Refusal { Run-Install } -NoAdb $true
    Reset-Case 'present'
    [IO.File]::WriteAllText($leasePath, '{broken')
    Assert-Refusal { Run-Install } -NoAdb $true
    foreach ($modeValue in @('disconnected', 'check-fail', 'check-text', 'pull-fail', 'signer-mismatch', 'downgrade', 'wrong-package', 'lose-lease')) {
        Reset-Case $modeValue
        Assert-Refusal { Run-Install }
    }
    Reset-Case 'present'
    Assert-Refusal { Run-Install -Model 'WrongModel' }
    Reset-Case 'present'
    Assert-Refusal { Run-Install -Avd 'WrongAVD' }
    foreach ($modeValue in @('install-fail', 'false-success')) {
        Reset-Case $modeValue
        $caught = $false
        try { Run-Install } catch { $caught = $true }
        if (-not $caught) { throw 'An unconfirmed install was reported as successful.' }
        $cases++
    }
    Reset-Case 'present'
    Assert-Refusal { & (Join-Path $Root 'scripts/patch-for-device.ps1') -Replace } -NoAdb $true
    Write-Host "[scripts] data-preserving device install contracts passed ($cases cases)"
} finally {
    Remove-GeneratedPath -Root $env:TEMP -Path $caseRoot
}

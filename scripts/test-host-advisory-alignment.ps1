<#
.SYNOPSIS
    Check reviewed host libraries and preserve unrelated runtime dependency requests.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
. (Join-Path $Root 'scripts/build-advisories.ps1') -Root $Root
. (Join-Path $Root 'scripts/common.ps1')
Import-UserEnvironment -Name @('HUSHTELEGRAM_BUILD_WRAPPER')
$reportPath = Join-Path $Root 'patches/build/dependency-reports/build-dependencies.json'
$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$work = [IO.Path]::GetFullPath((Join-Path $temp ('hushtelegram-host-alignment-' + [guid]::NewGuid().ToString('N'))))
if ([IO.Path]::GetDirectoryName($work) -ine $temp) { throw 'The host alignment fixture escaped temp.' }
New-Item -ItemType Directory -Path $work | Out-Null

function Invoke-Report([string]$InitScript) {
    # Through the machine's build wrapper, which queues and bounds Gradle itself, or gradlew.bat
    # inside a queue slot when no wrapper is set, as the pre-push gate and the release stages start it.
    $tasks = @(':patches:buildDependencyReport', '--console=plain', '--dependency-verification=strict', '--rerun-tasks')
    if ($InitScript) { $tasks += @('--init-script', $InitScript) }
    $wrapper = $env:HUSHTELEGRAM_BUILD_WRAPPER
    if ($wrapper -and -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "HUSHTELEGRAM_BUILD_WRAPPER names $wrapper, which is not there."
    }
    $savedPreference = $ErrorActionPreference
    $global:LASTEXITCODE = 0
    try {
        $ErrorActionPreference = 'Continue'
        Push-Location -LiteralPath $Root
        try {
            if ($wrapper) {
                & $wrapper -ProjectDir $Root -Tasks $tasks | Out-Host
                $code = $LASTEXITCODE
            } else {
                # Names the queue's own functions don't use (see Invoke-InHushTelegramQueue).
                $gradlew = Join-Path $Root 'gradlew.bat'
                $gradleRoot = $Root
                $gradleTasks = $tasks
                $code = Invoke-InHushTelegramQueue -Job 'host advisory alignment' -ScriptBlock { & $gradlew -p $gradleRoot @gradleTasks }
            }
        } finally { Pop-Location }
    } finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw "The host alignment graph could not be generated (exit $code). Read the Gradle output above." }
}
function Assert-Version($Report, [string]$Configuration, [string]$Group, [string]$Name, [string]$Version) {
    $libraries = @($Report.Libraries | Where-Object {
        $_.Group -ceq $Group -and $_.Name -ceq $Name -and $_.Configurations -contains $Configuration
    })
    if ($libraries.Count -ne 1 -or $libraries[0].Version -cne $Version) {
        throw "Expected ${Group}:$Name $Version on $Configuration."
    }
}

try {
    $init = Join-Path $work 'unrelated-runtime.init.gradle'
    [IO.File]::WriteAllText($init, @'
allprojects { candidate ->
    if (candidate.path == ':patches:stub') {
        candidate.afterEvaluate {
            candidate.dependencies.add('runtimeOnly', 'org.apache.commons:commons-lang3:3.16.0')
            candidate.dependencies.add('runtimeOnly', 'org.apache.httpcomponents:httpclient:4.5.6')
            // Use the already-reviewed transitive versions that the host graphs carry.
            candidate.dependencies.add('runtimeOnly', 'org.apache.httpcomponents:httpcore:4.4.16')
            candidate.dependencies.add('runtimeOnly', 'commons-codec:commons-codec:1.17.1')
        }
    }
}
'@, [Text.UTF8Encoding]::new($false))
    Invoke-Report $init
    $report = Read-BuildDependencyReport $reportPath
    $listeners = @($report.Configurations | Where-Object {
        $_.EndsWith(':_internal-unified-test-platform-android-test-plugin-result-listener-gradle')
    })
    if ($listeners.Count -eq 0) { throw 'The Android Gradle result-listener graph is missing.' }
    foreach ($configuration in @('settings:classpath') + $listeners) {
        Assert-Version $report $configuration 'org.apache.commons' 'commons-lang3' '3.20.0'
        Assert-Version $report $configuration 'org.apache.httpcomponents' 'httpclient' '4.5.14'
    }
    Assert-Version $report ':patches:stub:runtimeClasspath' 'org.apache.commons' 'commons-lang3' '3.16.0'
    Assert-Version $report ':patches:stub:runtimeClasspath' 'org.apache.httpcomponents' 'httpclient' '4.5.6'
    Write-Host "[host-advisories] settings and $($listeners.Count) result listeners aligned; both unrelated runtime requests unchanged"
} finally {
    try { Invoke-Report '' } finally {
        $cleanup = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $work).Path)
        if ([IO.Path]::GetDirectoryName($cleanup) -ine $temp) { throw 'Refusing host-fixture cleanup outside temp.' }
        Remove-Item -LiteralPath $cleanup -Recurse -Force
    }
}
Write-Host '[host-advisories] scoped real Gradle resolution passed; the normal host report is restored'

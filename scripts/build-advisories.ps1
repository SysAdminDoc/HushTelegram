<#
.SYNOPSIS
    Scan the resolved build, test and provided dependency report with the release advisory policy.
.DESCRIPTION
    Generate the report with :patches:buildDependencyReport. This report includes the toolchain
    classpaths that the shipped SBOM deliberately excludes. Query failures stop the gate, and
    high, critical and unrated findings need a current exception specific to the build graph.

    The Gradle version the report names is held first to scripts/build-tool-advisories.txt, the
    maintainer advisories recorded for the build tool, which no library scan covers. A version
    inside a high or critical range stops the gate whatever OSV says about the libraries, and so
    does a list or a version that cannot be read. Tools are reported on a line of their own.
#>
[CmdletBinding()]
param([string]$Root, [string]$ReportPath, [string]$ExceptionsPath, [string]$ToolAdvisoriesPath)

. (Join-Path $PSScriptRoot 'release-advisories.ps1')

function Read-BuildDependencyReport {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The resolved build dependency report is missing: $Path. Run :patches:buildDependencyReport."
    }
    try { $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { throw "The build dependency report is not valid JSON: $Path. $($_.Exception.Message)" }
    if (($report.schemaVersion -isnot [int] -and $report.schemaVersion -isnot [long]) -or
            $report.schemaVersion -ne 1 -or [string]::IsNullOrWhiteSpace($report.gradleVersion)) {
        throw "The build dependency report must have schemaVersion 1 and its resolved Gradle version: $Path"
    }
    if ($report.configurations -isnot [array] -or $report.configurations.Count -eq 0 -or
            $report.components -isnot [array] -or $report.components.Count -eq 0) {
        throw "The build dependency report must list nonempty configuration and component arrays: $Path"
    }
    $configurations = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($configuration in $report.configurations) {
        if ($configuration -isnot [string] -or [string]::IsNullOrWhiteSpace($configuration) -or
                $configuration -match '[\r\n\t]' -or -not $configurations.Add($configuration)) {
            throw "The build dependency report has a blank, duplicate or invalid configuration: $Path"
        }
    }
    $libraries = New-Object Collections.Generic.List[object]
    $purls = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($component in $report.components) {
        foreach ($field in @('group', 'name', 'version', 'purl')) {
            $value = $component.$field
            if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value) -or $value -match '\s') {
                throw "The build dependency report has a component without a resolved ${field}: $Path"
            }
        }
        $expectedPurl = 'pkg:maven/' + [Uri]::EscapeDataString($component.group) + '/' +
            [Uri]::EscapeDataString($component.name) + '@' + [Uri]::EscapeDataString($component.version)
        if ($component.purl -cne $expectedPurl -or -not $purls.Add($component.purl)) {
            throw "The build dependency report has a mismatched or duplicate package URL: $($component.purl)"
        }
        if ($component.configurations -isnot [array] -or $component.configurations.Count -eq 0) {
            throw "The build dependency report gives $($component.purl) no configuration origin."
        }
        $origins = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($configuration in $component.configurations) {
            if ($configuration -isnot [string] -or -not $configurations.Contains($configuration) -or
                    -not $origins.Add($configuration)) {
                throw "The build dependency report gives $($component.purl) an unknown or duplicate configuration origin."
            }
        }
        $libraries.Add([pscustomobject]@{
            Group = $component.group; Name = $component.name; Version = $component.version
            Purl = $component.purl; Configurations = @($component.configurations)
        })
    }
    # The tool that ran the build, kept apart from the libraries it resolved: Gradle is no Maven
    # component of this graph, and OSV is never asked about it under a package URL made up for it.
    return [pscustomobject]@{ Path = $Path; Libraries = $libraries.ToArray(); Configurations = @($report.configurations)
        Tools = @([pscustomobject]@{ Name = 'gradle'; Version = [string]$report.gradleVersion }) }
}

function Invoke-BuildAdvisoryGate {
    param(
        [Parameter(Mandatory = $true)]$Report,
        [Parameter(Mandatory = $true)][string]$ExceptionsPath,
        [string]$ToolAdvisoriesPath,
        [datetime]$Today = [datetime]::Today
    )
    # The build tool first, against the maintainer advisories recorded for it. It needs no network,
    # and a clean answer about the libraries says nothing about the Gradle that resolved them.
    if (-not $ToolAdvisoriesPath) { $ToolAdvisoriesPath = Join-Path $PSScriptRoot 'build-tool-advisories.txt' }
    $toolRecords = @(Read-BuildToolAdvisories -Path $ToolAdvisoriesPath)
    $tools = @(@($Report.Tools) | Where-Object { $null -ne $_ })
    if ($tools.Count -eq 0) {
        throw 'The resolved build report names no build tool, so nothing was held to the build tool advisory list.'
    }
    $toolLines = @(foreach ($tool in $tools) {
        $toolVerdict = Test-BuildToolAdvisories -Tool $tool.Name -Version $tool.Version -Records $toolRecords
        foreach ($line in @($toolVerdict.Minor)) { Write-Host "[build advisories] build tool, below high, let through: $line" }
        if (-not $toolVerdict.Valid) { throw $toolVerdict.Reason }
        ("[build advisories] build tool $($tool.Name) $($tool.Version) is outside the high and critical ranges of the " +
            "$($toolVerdict.Recorded) maintainer advisories recorded for it, last read " +
            "$($toolVerdict.LastRead.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)). " +
            'Nothing newer was asked about.')
    })
    $exceptions = @(Read-AdvisoryExceptions -Path $ExceptionsPath -Today $Today)
    $findings = @(Get-SbomAdvisories -Sbom $Report)
    $verdict = Test-AdvisoryFindings -Findings $findings -Exceptions $exceptions
    foreach ($line in @($verdict.Minor)) { Write-Host "[build advisories] below high, let through: $line" }
    foreach ($line in @($verdict.Excused)) { Write-Host "[build advisories] accepted: $line" }
    $reasons = @()
    if (@($verdict.Refused).Count -gt 0) {
        $reasons += ('OSV reports high, critical or unrated advisories on the resolved build/test/provided graph: ' +
            ($verdict.Refused -join '; ') + '. Move to a version without them, or record why the advisory ' +
            'does not apply in scripts/build-advisory-exceptions.txt with a date within 90 days.')
    }
    if (@($verdict.Stale).Count -gt 0) {
        $reasons += ('scripts/build-advisory-exceptions.txt accepts advisories OSV no longer reports on the ' +
            'resolved build graph: ' + ($verdict.Stale -join '; ') + '. Take them out.')
    }
    if ($reasons.Count -gt 0) { throw ($reasons -join ' ') }
    $said = if ($findings.Count -eq 0) { 'no advisory' } else { "$($findings.Count) advisories, none refused" }
    Write-Host "[build advisories] OSV has $said for $(@($Report.Libraries).Count) resolved libraries"
    foreach ($line in $toolLines) { Write-Host $line }
}

# Dot-sourcing exposes the reader and gate for isolated recorded-response contracts.
if ($MyInvocation.InvocationName -eq '.') { return }
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if (-not $ReportPath) { $ReportPath = Join-Path $Root 'patches/build/dependency-reports/build-dependencies.json' }
if (-not $ExceptionsPath) { $ExceptionsPath = Join-Path $Root 'scripts/build-advisory-exceptions.txt' }
if (-not $ToolAdvisoriesPath) { $ToolAdvisoriesPath = Join-Path $Root 'scripts/build-tool-advisories.txt' }
$resolvedReport = Read-BuildDependencyReport -Path $ReportPath
Invoke-BuildAdvisoryGate -Report $resolvedReport -ExceptionsPath $ExceptionsPath -ToolAdvisoriesPath $ToolAdvisoriesPath

<#
.SYNOPSIS
    Reject missing, inconsistent or altered packaging evidence before accepting a receipt.
#>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $Root 'scripts/native-packaging.ps1')
. (Join-Path $Root 'scripts/checks/native-packaging-fixture.ps1')
$cases = 0
function Check-Packaging($Evidence) {
    return Test-NativePackagingEvidence -NativeLibraries $Evidence.NativeLibraries -ZipAlignment $Evidence.ZipAlignment -ExpectedSourceSha256 ('b' * 64)
}
$valid = Check-Packaging (New-NativePackagingFixture)
if (-not $valid.Valid) { throw "Valid compressed native evidence was refused: $($valid.Reason)" }
$cases++
foreach ($mutate in @(
    { param($e) $e.NativeLibraries = $null },
    { param($e) $e.NativeLibraries.schemaVersion = 2 },
    { param($e) $e.NativeLibraries.passed = $false },
    { param($e) $e.NativeLibraries.passed = 'true' },
    { param($e) $e.NativeLibraries.failures = @('native check failed') },
    { param($e) $e.NativeLibraries.failures = $null },
    { param($e) $e.NativeLibraries.required64BitLoadAlignmentBytes = 4096 },
    { param($e) $e.NativeLibraries.sourceApkSha256 = ('c' * 64) },
    { param($e) $e.NativeLibraries.stockApkSha256 = 'invalid' },
    { param($e) $e.NativeLibraries.checkerSha256 = '' },
    { param($e) $e.NativeLibraries.patched.entries = @(); $e.NativeLibraries.patched.nativeEntryCount = 0 },
    { param($e) $e.NativeLibraries.patched.nativeEntryCount = 2 },
    { param($e) $e.NativeLibraries.patched.entries += @($e.NativeLibraries.patched.entries[0]); $e.NativeLibraries.patched.nativeEntryCount = 2 },
    { param($e) $e.NativeLibraries.patched.entries[0].name = 'lib/arm64-v8a/changed.so' },
    { param($e) $e.NativeLibraries.patched.entries[0].abi = 'x86_64' },
    { param($e) $e.NativeLibraries.patched.entries[0].sha256 = ('c' * 64) },
    { param($e) $e.NativeLibraries.patched.entries[0].size = 65 },
    { param($e) $e.NativeLibraries.patched.entries[0].compressionMethod = 0; $e.NativeLibraries.patched.entries[0].compression = 'STORE' },
    { param($e) $e.NativeLibraries.patched.entries[0].compression = 'STORE' },
    { param($e) $e.NativeLibraries.patched.entries[0].elf = $null },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.classBits = 32 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.machine = 62 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.byteOrder = 'unknown' },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments = @() },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].alignmentBytes = 4096 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].alignmentBytes = 24576 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].offset = 1 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].fileSize = 65 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].memorySize = 31 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].virtualAddress = -1 },
    { param($e) $e.NativeLibraries.patched.entries[0].elf.loadSegments[0].alignmentBytes = '16384' },
    { param($e) $e.ZipAlignment = $null },
    { param($e) $e.ZipAlignment.passed = $false },
    { param($e) $e.ZipAlignment.passed = 'true' },
    { param($e) $e.ZipAlignment.pageSizeKb = 4 },
    { param($e) $e.ZipAlignment.alignmentBytes = 8 },
    { param($e) $e.ZipAlignment.apkSha256 = ('a' * 64) },
    { param($e) $e.ZipAlignment.toolSha256 = '' },
    { param($e) $e.ZipAlignment.buildToolsVersion = '' }
)) {
    $evidence = New-NativePackagingFixture
    & $mutate $evidence
    if ((Check-Packaging $evidence).Valid) { throw "Altered native packaging evidence was accepted (case $cases)." }
    $cases++
}
# 32-bit libraries retain their own legal alignment. The 16 KB rule belongs to relevant 64-bit LOADs.
$evidence = New-NativePackagingFixture
foreach ($side in @('stock', 'patched')) {
    $entry = $evidence.NativeLibraries.$side.entries[0]
    $entry.name = 'lib/armeabi-v7a/libfixture.so'; $entry.abi = 'armeabi-v7a'
    $entry.elf.classBits = 32; $entry.elf.machine = 40; $entry.elf.requiredLoadAlignmentBytes = 0
    $entry.elf.loadSegments[0].alignmentBytes = 4096
}
if (-not (Check-Packaging $evidence).Valid) { throw 'Valid 32-bit LOAD alignment was refused.' }
$cases++
Write-Host "[scripts] native packaging evidence contracts passed ($cases cases)"

<# Host-only checks. No ADB, device, signing key or account is accessed. #>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-settings-navigation-device.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("hushtelegram-navigation-host-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$leasePath = Join-Path $testRoot 'emulator-9998.json'
$previousLeaseDirectory = $env:HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY
$count = 0
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:count++ }
function Assert-Fails([scriptblock]$Action, [string]$Pattern) {
    $caught = $null
    try { & $Action } catch { $caught = $_ }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -like $Pattern) "Expected failure $Pattern; got $caught"
}
function Write-TestLease {
    $now = [DateTimeOffset]::UtcNow
    $lease = [ordered]@{ schemaVersion = 1; serial = 'emulator-9998'; project = 'HushTelegram'; chatIdentity = 'navigation-host';
        ownershipToken = 'owned-token'; acquiredUtc = $now.AddMinutes(-1).ToString('o'); expiresUtc = $now.AddHours(1).ToString('o') }
    $lease | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    return $lease
}
function New-TestSession {
    param([string]$OwnedLease = $leasePath, [int]$Api = 36)
    $state = [pscustomobject]@{
        Calls = [Collections.Generic.List[string]]::new(); Model = 'sdk-test'; Fingerprint = 'fixture/build'; Profile = 'Navigation_API36'; Api = $Api;
        System = @{ font_scale = '1.1'; system_locales = 'de-DE,en-US'; accelerometer_rotation = '0'; user_rotation = '3' };
        Secure = @{ navigation_mode = '0'; default_input_method = 'fixture.ime/.Keyboard'; enabled_input_methods = 'fixture.ime/.Keyboard;123';
            accessibility_enabled = '1'; enabled_accessibility_services = 'fixture.reader/.Service'; touch_exploration_enabled = '0' };
        Global = @{ development_settings_enabled = '1' }; Overlay = 'threebutton'; AppLocale = 'de-DE';
        LoseLeaseAt = 0; FailKey = $null; MalformedNamespace = $null; EmptyNamespace = $null; FailAdb = $false; FailTimeout = $false;
        ExtraInventory = @{}; Reverse = @{}; FontRewrites = 0
    }
    $invoker = {
        param($Executable, [string[]]$Arguments)
        if ($Arguments.Count -lt 3 -or $Arguments[0] -cne '-s' -or $Arguments[1] -cne 'emulator-9998') { throw 'Device selection was implicit.' }
        $line = $Arguments[2..($Arguments.Length - 1)] -join ' '
        $state.Calls.Add($line)
        $exit = if ($state.FailAdb) { 1 } else { 0 }
        $output = @()
        if ($line -ceq 'get-state') { $output = @('device') }
        elseif ($line -ceq 'get-serialno') { $output = @('emulator-9998') }
        elseif ($line -ceq 'shell am get-current-user') { $output = @('0') }
        elseif ($line -ceq 'shell getprop ro.product.model') { $output = @($state.Model) }
        elseif ($line -ceq 'shell getprop ro.build.version.sdk') { $output = @([string]$state.Api) }
        elseif ($line -ceq 'shell getprop ro.build.fingerprint') { $output = @($state.Fingerprint) }
        elseif ($line -ceq 'emu avd name') { $output = @($state.Profile, 'OK') }
        elseif ($line -match '^shell settings(?: --user 0)? list (system|secure|global)$') {
            if ($state.Api -eq 28 -and $line.Contains('--user')) { throw 'API28 does not accept --user for list.' }
            $space = $Matches[1]; $values = $state.$space
            if ($state.MalformedNamespace -eq $space) { $output = @('not-a-setting') }
            elseif ($state.EmptyNamespace -eq $space) { $output = @() }
            else {
                $output = @($values.Keys | Sort-Object | ForEach-Object { "$_=$($values[$_])" })
                if ($state.ExtraInventory.ContainsKey($space)) { $output += $state.ExtraInventory[$space] }
            }
        } elseif ($line -match '^shell settings(?: --user 0)? (put|delete) (system|secure|global) ([A-Za-z_][A-Za-z0-9_.]*)(?: (.*))?$') {
            $operation = $Matches[1]; $values = $state.($Matches[2]); $key = $Matches[3]; $value = $Matches[4]
            if ($state.Api -eq 28 -and $operation -eq 'delete' -and $line.Contains('--user')) { throw 'API28 does not accept --user for delete.' }
            if ($state.FailKey -ceq $key) { $exit = 41 }
            elseif ($operation -eq 'delete') {
                $values.Remove($key)
                if ($key -ceq 'font_scale' -and $state.FontRewrites -gt 0) {
                    $values[$key] = '1.0'; $state.FontRewrites--
                }
            }
            else { $values[$key] = $value.Substring(1, $value.Length - 2).Replace("'\''", "'") }
        } elseif ($line -ceq 'shell cmd overlay list --user 0') {
            $output = @('android', '[ ] com.android.internal.systemui.navbar.threebutton', '[ ] com.android.internal.systemui.navbar.gestural')
            $index = if ($state.Overlay -eq 'threebutton') { 1 } else { 2 }
            $output[$index] = $output[$index].Replace('[ ]', '[x]')
        } elseif ($line -match '^shell cmd overlay enable-exclusive --category --user 0 com.android.internal.systemui.navbar.(threebutton|gestural)$') {
            $state.Overlay = $Matches[1]; $state.Secure.navigation_mode = if ($state.Overlay -eq 'threebutton') { '0' } else { '2' }
        } elseif ($line -ceq 'shell cmd locale get-app-locales fixture.target --user 0') {
            $output = @("Locales for fixture.target for user 0 are [$($state.AppLocale)]")
        } elseif ($line -match '^shell cmd locale set-app-locales fixture.target --user 0(?: --locales ([A-Za-z0-9,-]+))?$') {
            $state.AppLocale = [string]$Matches[1]
        } elseif ($line -ceq 'reverse --list') {
            $output = @($state.Reverse.Keys | ForEach-Object { "fixture $_ $($state.Reverse[$_])" })
        } elseif ($line -match '^reverse --remove (localabstract:hushtelegram-nav-[0-9a-f]{32})$') {
            $state.Reverse.Remove($Matches[1])
        } elseif ($line -ceq 'shell harmless') {
            $output = if ($state.FailTimeout) { @('INSTRUMENTATION_STATUS: stream=NAV_STAGE:wait for main queue idle') } else { @('ok') }
        }
        else { throw "Unexpected fake ADB: $line" }
        if ($state.LoseLeaseAt -eq $state.Calls.Count) {
            $lease = Get-Content -LiteralPath $OwnedLease -Raw | ConvertFrom-Json
            $lease.ownershipToken = 'another-owner'
            $lease | ConvertTo-Json | Set-Content -LiteralPath $OwnedLease -Encoding UTF8
        }
        [pscustomobject]@{ ExitCode = $exit; Output = $output; TimedOut = ($state.FailTimeout -and $line -ceq 'shell harmless') }
    }.GetNewClosure()
    $session = New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' $Api $testRoot 'owned-token' 'navigation-host' $invoker
    [pscustomobject]@{ Session = $session; State = $state }
}
function New-TestSnapshot($Test) {
    [pscustomobject]@{ SchemaVersion = 1; Serial = $Test.Session.Serial; Model = $Test.Session.Model;
        Avd = $Test.Session.Avd; Api = $Test.Session.Api; Fingerprint = 'fixture/build'; Package = 'fixture.target';
        Settings = @(Get-NavigationSettings $Test.Session); Overlay = (Get-NavigationOverlay $Test.Session);
        AppLocale = (Get-NavigationAppLocale $Test.Session 'fixture.target'); LeaseSocket = $null; LeasePort = $null }
}
function Request-TestGrant($Server, [string]$Capability) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect([Net.IPAddress]::Loopback, $Server.Port)
        $stream = $client.GetStream(); $stream.ReadTimeout = 3000
        $bytes = [Text.Encoding]::ASCII.GetBytes($Capability + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
        return $stream.ReadByte()
    } finally { $client.Dispose() }
}
try {
    [void](Write-TestLease)
    $env:HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY = $null
    Assert-Fails { New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' 36 $testRoot 'owned-token' 'navigation-host' } '*Configure HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY*'
    foreach ($relativePool in @('relative-pool', 'C:relative-pool', '\relative-pool')) {
        $env:HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY = $relativePool
        Assert-Fails { New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' 36 '' 'owned-token' 'navigation-host' } '*absolute shared pool path*'
    }
    $sharedPool = Join-Path $testRoot 'shared-pool'
    New-Item -ItemType Directory -Path $sharedPool | Out-Null
    $env:HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY = $sharedPool
    Assert-Fails { New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' 36 $testRoot 'owned-token' 'navigation-host' } '*shared whole-device lease directory*'
    $production = New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' 36 '' 'owned-token' 'navigation-host'
    Assert-True ($production.Lease -ceq (Join-Path $sharedPool 'emulator-9998.json')) 'Production lease root was not pinned to host configuration.'
    $explicit = New-NavigationSession 'fake-adb' 'emulator-9998' 'sdk-test' 'Navigation_API36' 36 $sharedPool 'owned-token' 'navigation-host'
    Assert-True ($explicit.Lease -ceq $production.Lease) 'The configured shared pool was refused when supplied explicitly.'
    $test = New-TestSession; $server = Start-NavigationLeaseServer $test.Session
    try {
        Assert-True ((Request-TestGrant $server $server.Capability) -eq 1) 'An owned lease was refused.'
        Assert-True ((Request-TestGrant $server ('0' * 64)) -eq 0) 'The server accepted another run capability.'
        Assert-True ((Request-TestGrant $server ('x' * 65)) -eq 0) 'An oversized lease request was accepted.'
        foreach ($field in @('ownershipToken', 'serial', 'project', 'chatIdentity', 'expiresUtc', 'acquiredUtc', 'schemaVersion')) {
            $changed = Write-TestLease
            $changed[$field] = switch ($field) {
                expiresUtc { [DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o') }
                acquiredUtc { [DateTimeOffset]::UtcNow.AddHours(2).ToString('o') }
                schemaVersion { '1' }; default { 'replacement' }
            }
            $changed | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
            Assert-True ((Request-TestGrant $server $server.Capability) -eq 0) "Live server missed changed $field."
        }
        Remove-Item -LiteralPath $leasePath
        Assert-True ((Request-TestGrant $server $server.Capability) -eq 0) 'A deleted lease still granted input.'
        [void](Write-TestLease)
        Assert-True ((Request-TestGrant $server $server.Capability) -eq 1) 'Restored ownership was not rechecked.'
        Assert-True ($test.State.Calls.Count -eq 0) 'Host ownership checks reached a device.'
        $stalled = [Net.Sockets.TcpClient]::new()
        try {
            $stalled.Connect([Net.IPAddress]::Loopback, $server.Port)
            $stalled.GetStream().WriteByte(120)
            $stopClock = [Diagnostics.Stopwatch]::StartNew()
            Stop-NavigationLeaseServer $server; $server = $null
            Assert-True ($stopClock.ElapsedMilliseconds -lt 3000) 'A stalled request prevented bounded server shutdown.'
        } finally { $stalled.Dispose() }
    } finally { if ($server) { Stop-NavigationLeaseServer $server } }
    $legacy = New-TestSession -Api 28
    $legacySettings = @(Get-NavigationSettings $legacy.Session)
    Set-NavigationSetting $legacy.Session 'secure' 'show_ime_with_hard_keyboard' $null $false
    foreach ($id in @('system/font_scale', 'system/system_locales', 'system/accelerometer_rotation', 'system/user_rotation',
        'secure/navigation_mode', 'secure/default_input_method', 'secure/enabled_input_methods', 'secure/show_ime_with_hard_keyboard',
        'secure/selected_input_method_subtype', 'secure/input_methods_subtype_history', 'secure/accessibility_enabled',
        'secure/enabled_accessibility_services', 'secure/touch_exploration_enabled', 'global/development_settings_enabled', 'global/debug.force_rtl')) {
        $row = @($legacySettings | Where-Object { "$($_.Namespace)/$($_.Key)" -ceq $id })
        Assert-True ($row.Count -eq 1) "API28 settings snapshot omitted or duplicated $id."
    }
    Assert-True ($legacy.State.Calls -ccontains 'shell settings list system' -and
        $legacy.State.Calls -ccontains 'shell settings delete secure show_ime_with_hard_keyboard') 'API28 used unsupported settings arguments.'
    Assert-True (@($legacy.State.Calls | Where-Object { $_ -ceq 'shell am get-current-user' }).Count -eq 4) 'API28 lost its explicit current-user guard.'
    Assert-True (($legacySettings | Where-Object { $_.Key -ceq 'font_scale' }).Value -ceq '1.1') 'API28 lost a present setting value.'
    $absent = $legacySettings | Where-Object { $_.Key -ceq 'show_ime_with_hard_keyboard' }
    Assert-True (-not $absent.Exists -and $null -eq $absent.Value) 'API28 confused an absent setting with an empty value.'
    foreach ($api in @(28, 36)) {
        $names = New-TestSession -Api $api
        $names.State.ExtraInventory.system = @('', 'volume_music_aa:bb:cc=3', '123.vendor=value=with=equals', 'vendor/key=value', 'Vendor.Key=one', 'vendor.key=two')
        $rows = @(Get-NavigationSettings $names.Session)
        Assert-True ($rows.Count -eq $legacySettings.Count) 'Unrelated provider names entered the recovery allowlist.'
        Assert-True (($rows | Where-Object { $_.Key -ceq 'font_scale' }).Value -ceq '1.1') 'Unrelated provider names corrupted a required value.'
        $names.State.ExtraInventory.system = @('font_scale=2')
        Assert-Fails { Get-NavigationSettings $names.Session } '*duplicate*inventory*'
        $names.State.ExtraInventory.system = @('=value')
        Assert-Fails { Get-NavigationSettings $names.Session } '*Malformed*inventory*'
        $names.State.ExtraInventory.system = @("broken`rname=value")
        Assert-Fails { Get-NavigationSettings $names.Session } '*Malformed*inventory*'
    }
    foreach ($field in @('schemaVersion', 'serial', 'project', 'chatIdentity', 'ownershipToken', 'expiresUtc', 'acquiredUtc')) {
        $lease = Write-TestLease
        $lease[$field] = switch ($field) {
            schemaVersion { 2 }; expiresUtc { [DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o') };
            acquiredUtc { [DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o') }; default { 'wrong' }
        }
        $lease | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
        $test = New-TestSession
        Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*whole-device lease*'
        Assert-True ($test.State.Calls.Count -eq 0) 'An invalid lease reached ADB.'
    }
    [void](Write-TestLease)
    $lease = Write-TestLease; $lease.schemaVersion = '1'
    $lease | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    $test = New-TestSession
    Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*whole-device lease*'
    Assert-True ($test.State.Calls.Count -eq 0) 'A string schema version reached ADB.'
    [void](Write-TestLease)
    $test = New-TestSession
    [void](Invoke-NavigationAdb $test.Session @('shell', 'harmless'))
    [void](Invoke-NavigationAdb $test.Session @('shell', 'harmless'))
    Assert-True ($test.State.Calls.Count -eq 16 -and @($test.State.Calls | Where-Object { $_ -ceq 'emu avd name' }).Count -eq 2) 'Identity was not refreshed before every command.'
    $test.State.Fingerprint = 'replacement/build'
    Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*build changed*'
    Assert-True (@($test.State.Calls | Where-Object { $_ -ceq 'shell harmless' }).Count -eq 2) 'Changed build reached the operation.'
    foreach ($identity in @('Model', 'Profile')) {
        [void](Write-TestLease); $test = New-TestSession; $test.State.$identity = 'wrong'
        $pattern = if ($identity -eq 'Model') { '*identity*' } else { '*profile*' }
        Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } $pattern
    }
    [void](Write-TestLease); $test = New-TestSession; $test.State.LoseLeaseAt = 3
    Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*whole-device lease*'
    Assert-True ($test.State.Calls.Count -eq 3) 'Lease loss did not stop the next identity command.'
    [void](Write-TestLease); $test = New-TestSession; $test.State.FailAdb = $true
    Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*ADB*failed*'
    $test = New-TestSession; $test.State.FailTimeout = $true
    Assert-Fails { Invoke-NavigationAdb $test.Session @('shell', 'harmless') } '*timed out*Last navigation stage: wait for main queue idle.*'
    foreach ($kind in @('MalformedNamespace', 'EmptyNamespace')) {
        [void](Write-TestLease); $test = New-TestSession; $test.State.$kind = 'secure'
        Assert-Fails { Get-NavigationSettings $test.Session } '*settings inventory*'
    }
    [void](Write-TestLease); $test = New-TestSession
    $snapshot = New-TestSnapshot $test
    $truncated = $snapshot.PSObject.Copy(); $truncated.Settings = @($snapshot.Settings | Where-Object { $_.Key -ne 'font_scale' })
    $beforeCalls = $test.State.Calls.Count
    Assert-Fails { Restore-NavigationEnvironment $test.Session $truncated } '*Incomplete*record*'
    Assert-True ($test.State.Calls.Count -eq $beforeCalls) 'An incomplete recovery record reached ADB.'
    $unsafeLocale = $snapshot.PSObject.Copy(); $unsafeLocale.AppLocale = 'en;invalid'
    Assert-Fails { Restore-NavigationEnvironment $test.Session $unsafeLocale } '*Invalid*overlay*'
    Assert-True ($test.State.Calls.Count -eq $beforeCalls) 'An invalid recovery locale reached ADB.'
    Set-NavigationSetting $test.Session 'system' 'font_scale' '2'
    Set-NavigationSetting $test.Session 'secure' 'show_ime_with_hard_keyboard' '1'
    Set-NavigationOverlay $test.Session 'com.android.internal.systemui.navbar.gestural'
    $test.State.Secure.default_input_method = 'replacement.ime/.Keyboard'
    $test.State.Secure.enabled_accessibility_services = 'replacement.reader/.Service'
    $test.State.Secure.accessibility_temporary = '1'; $test.State.AppLocale = 'ar'
    Restore-NavigationEnvironment $test.Session $snapshot
    Assert-True ($test.State.System.font_scale -ceq '1.1' -and $test.State.System.user_rotation -ceq '3') 'Exact font/rotation restoration failed.'
    Assert-True (-not $test.State.Secure.ContainsKey('show_ime_with_hard_keyboard') -and -not $test.State.Secure.ContainsKey('accessibility_temporary')) 'Absent settings were not restored by deletion.'
    Assert-True ($test.State.Secure.default_input_method -ceq 'fixture.ime/.Keyboard' -and $test.State.Secure.enabled_accessibility_services -ceq 'fixture.reader/.Service') 'IME/a11y state was not restored.'
    Assert-True ($test.State.AppLocale -ceq 'de-DE' -and $test.State.Overlay -ceq 'threebutton') 'Locale/navigation state was not restored.'
    $noNavigation = $snapshot.PSObject.Copy()
    $noNavigation.Settings = @($snapshot.Settings | ForEach-Object {
        if ($_.Key -ceq 'navigation_mode') { [pscustomobject]@{ Namespace = 'secure'; Key = 'navigation_mode'; Exists = $false; Value = $null } }
        else { $_ }
    })
    Set-NavigationOverlay $test.Session 'com.android.internal.systemui.navbar.gestural'
    Restore-NavigationEnvironment $test.Session $noNavigation
    Assert-True (-not $test.State.Secure.ContainsKey('navigation_mode') -and $test.State.Overlay -ceq 'threebutton') 'Overlay restoration recreated an originally absent navigation setting.'
    $font = New-TestSession -Api 28; $font.State.System.Remove('font_scale')
    $fontSnapshot = New-TestSnapshot $font
    Set-NavigationSetting $font.Session 'system' 'font_scale' '2'; $font.State.FontRewrites = 1
    Restore-NavigationEnvironment $font.Session $fontSnapshot
    Assert-True (-not $font.State.System.ContainsKey('font_scale')) 'Framework default persistence recreated an absent font setting.'
    Assert-True ($font.State.FontRewrites -eq 0) 'The font restoration regression did not exercise framework persistence.'
    Set-NavigationSetting $font.Session 'system' 'font_scale' '2'; $font.State.FontRewrites = 2
    Assert-Fails { Restore-NavigationEnvironment $font.Session $fontSnapshot } '*font_scale*differs*'
    Assert-True ($font.State.FontRewrites -eq 0) 'Repeated font drift was hidden or retried without a bound.'
    foreach ($field in @('Model', 'Avd', 'Serial', 'Api', 'Fingerprint', 'SchemaVersion')) {
        $wrong = $snapshot.PSObject.Copy(); $wrong.$field = if ($field -in @('Api', 'SchemaVersion')) { 999 } else { 'another-device' }
        $calls = $test.State.Calls.Count
        Assert-Fails { Restore-NavigationEnvironment $test.Session $wrong } '*another target*'
        Assert-True ($test.State.Calls.Count -eq $calls) 'Another device recovery record reached ADB.'
    }
    Assert-Fails { Assert-NavigationRecoveryIdentity $test.Session $snapshot 'another.package' } '*another target*'
    $mapping = $snapshot.PSObject.Copy(); $mapping.LeaseSocket = 'hushtelegram-nav-' + ('a' * 32); $mapping.LeasePort = 45678
    $mapping = $mapping | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $remote = 'localabstract:' + $mapping.LeaseSocket
    $test.State.Reverse[$remote] = 'tcp:45679'
    Assert-Fails { Remove-NavigationLeaseReverse $test.Session $mapping } '*another endpoint*'
    Assert-True ($test.State.Reverse.ContainsKey($remote)) 'Recovery removed another endpoint mapping.'
    $test.State.Reverse[$remote] = 'tcp:45678'; $test.State.Reverse['tcp:9999'] = 'tcp:9999'
    Remove-NavigationLeaseReverse $test.Session $mapping
    Assert-True (-not $test.State.Reverse.ContainsKey($remote) -and $test.State.Reverse.ContainsKey('tcp:9999')) 'Recovery changed unrelated reverse mappings.'
    Set-NavigationSetting $test.Session 'system' 'font_scale' '2'; $test.State.FailKey = 'font_scale'
    Assert-Fails { Restore-NavigationEnvironment $test.Session $snapshot } '*restoration*'
    $test.State.FailKey = $null; [void](Write-TestLease); Restore-NavigationEnvironment $test.Session $snapshot
    $test.State.LoseLeaseAt = $test.State.Calls.Count + 1
    Assert-Fails { Restore-NavigationEnvironment $test.Session $snapshot } '*whole-device lease*'
    Assert-True ($test.State.Calls[-1] -ceq 'get-state') 'Restoration used a device after lease loss.'
    Assert-True ((ConvertTo-NavigationShellLiteral "a'b;literal") -ceq "'a'\''b;literal'") 'Settings values were not safely quoted.'
    Assert-Fails { ConvertTo-NavigationShellLiteral "a`nb" } '*Multiline*'
    $shell = (Get-Command pwsh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Path
    $timed = Invoke-NavigationProcess $shell @('-NoProfile', '-Command', 'Start-Sleep -Seconds 5') 100
    Assert-True ($timed.TimedOut -and $timed.ExitCode -eq -1) 'A stalled child command was not bounded.'
    $echoScript = Join-Path $testRoot 'argv.ps1'
    [IO.File]::WriteAllText($echoScript, 'param([string]$Value) [Console]::WriteLine($Value)')
    $literal = 'literal "quoted" \ tail\'
    $echo = Invoke-NavigationProcess $shell @('-NoProfile', '-File', $echoScript, '-Value', $literal) 15000
    Assert-True (-not $echo.TimedOut -and $echo.ExitCode -eq 0 -and $echo.Output.Count -eq 1 -and $echo.Output[0] -ceq $literal) 'Native argument quoting failed.'

    $checks = @{}
    foreach ($name in @('localizedServiceTree', 'backAndNavigationMirrored', 'effectiveFontScale', 'systemBackNested',
        'nestedScrollRetained', 'imeFirstDismissal', 'systemBackSearch', 'homeScrollRetained',
        'queryAndScrollRotationRestored', 'systemBackClosesHome', 'switchesRetained', 'switchStatesInServiceTree', 'accountsRetained', 'processLocaleRestored')) { $checks[$name] = $true }
    $result = [ordered]@{ schemaVersion = 1; runId = 'run'; api = 36; mode = 'button'; locale = 'en'; fontScale = '1';
        localeScope = 'process-local configuration'; checks = $checks; passed = $true }
    function Result-Lines {
        'NAV_RESULT:' + ($result | ConvertTo-Json -Depth 5 -Compress)
        'INSTRUMENTATION_CODE: -1'
    }
    [void](Read-NavigationResult (Result-Lines) 'run' 36 'button' 'en' '1')
    Assert-True $true 'Complete button result failed.'
    $checks.imeFirstDismissal = $false
    Assert-Fails { Read-NavigationResult (Result-Lines) 'run' 36 'button' 'en' '1' } '*incomplete*'
    $checks.imeFirstDismissal = $true; $result.passed = 'true'
    Assert-Fails { Read-NavigationResult (Result-Lines) 'run' 36 'button' 'en' '1' } '*incomplete*'
    $result.passed = $true; $result.mode = 'gesture'; $checks.cancelledGestureRetainsState = $true
    $result.cancelledGestureProgressPixels = 0; $result.committedGestureProgressPixels = 100
    Assert-Fails { Read-NavigationResult (Result-Lines) 'run' 36 'gesture' 'en' '1' } '*incomplete*'
    $result.cancelledGestureProgressPixels = 100
    [void](Read-NavigationResult (Result-Lines) 'run' 36 'gesture' 'en' '1')
    Assert-True $true 'Complete gesture result failed.'
    Assert-Fails { Read-NavigationResult @('INSTRUMENTATION_CODE: -1') 'run' 36 'gesture' 'en' '1' } '*no unique*'
    Assert-Fails { Read-NavigationResult ((Result-Lines) + (Result-Lines)) 'run' 36 'gesture' 'en' '1' } '*no unique*'
    Assert-Fails { Read-NavigationResult (Result-Lines) 'another-run' 36 'gesture' 'en' '1' } '*incomplete*'
    Write-Host "[navigation] $count host assertions passed; no device acceptance was run"
} finally {
    $env:HUSHTELEGRAM_DEVICE_LEASE_DIRECTORY = $previousLeaseDirectory
    Remove-GeneratedPath -Root ([IO.Path]::GetTempPath().TrimEnd('\')) -Path $testRoot
}

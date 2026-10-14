<#
.SYNOPSIS
    The advisory gate: what OSV knows about the libraries a release's SBOM lists, and whether the
    release may go out with them.

.DESCRIPTION
    Dot-sourced by build-release-receipt.ps1 and validate-release-facts.ps1, beside
    release-receipt.ps1, whose Read-ReleaseSbom reads the SBOM these functions take.

    Gradle's dependency verification (gradle/verification-metadata.xml) says an artifact is the one
    somebody checked the hash of. It says nothing about the advisories published against that
    version since, and the libraries a bundle carries are chosen by conflict resolution several
    levels down, so nobody reads them one by one. This asks OSV (https://api.osv.dev/v1/query)
    about every library the SBOM lists, one query per package URL, and refuses a release that
    carries a high or critical advisory.

    High or critical means OSV's own label says so (the GitHub advisory database's, which every
    Maven advisory there carries) or a CVSS 3 vector scores 7.0 or more, whichever is worse. An
    advisory with neither is held as serious until somebody reads it: "OSV couldn't say" is not
    "fine". Moderate and low advisories are printed and let through.

    An advisory can be accepted in scripts/advisory-exceptions.txt for one package, until a date
    at most 90 days out, with the reason it doesn't apply to what the bundle does with that
    library. An exception past its date covers nothing, and one that matches no advisory any more
    fails the run, as the manifest allowlist does: an exception that outlives its advisory stops
    being a review.

    OSV has to answer. A query that fails, or an answer this can't read, stops the run rather than
    reading as no advisories. -SkipAdvisoryCheck is the way through for offline work, and it says
    so in a warning. The pre-push hook never passes it, so the index push that hands a release to
    Manager users asks OSV again.
#>

function Read-AdvisoryExceptions {
    <#
    .SYNOPSIS
        The accepted advisories, one per line: "<advisory> <group>:<name> <yyyy-MM-dd> <reason>".
    .DESCRIPTION
        The advisory is OSV's id or one of its aliases (a CVE, say). The date is the last day the
        exception holds on this computer's calendar (-Today defaults to the local date, not UTC's),
        at most 90 days after -Today, so accepting an advisory means reading it
        again at least that often. The reason is at least three words: an exception is a claim
        about how the bundle uses the library, and a claim nobody wrote down can't be checked.
        Blank lines and # comments are ignored. A line in any other shape stops the run, naming
        it, rather than being skipped: a skipped exception would fail the release for a reason
        nobody could see in the file.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [datetime]$Today = [datetime]::Today
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The advisory exception list is missing: $Path"
    }
    $entries = New-Object System.Collections.Generic.List[object]
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $number = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $number++
        $text = ([string]$line).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $shape = [regex]::Match($text,
            '^(?<id>[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9._-]+)\s+(?<package>[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)\s+(?<until>\S+)\s+(?<reason>.*\S)$')
        if (-not $shape.Success) {
            throw ("Line $number of $Path is not ""<advisory> <group>:<name> <yyyy-MM-dd> <reason>"": $text")
        }
        $until = [datetime]::MinValue
        if (-not [datetime]::TryParseExact($shape.Groups['until'].Value, 'yyyy-MM-dd',
                [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$until)) {
            throw "Line $number of $Path gives $($shape.Groups['until'].Value) as the last day, which isn't a yyyy-MM-dd date: $text"
        }
        $advisory = $shape.Groups['id'].Value
        $package = $shape.Groups['package'].Value
        if ($until -gt $Today.AddDays(90)) {
            throw ("Line $number of $Path accepts $advisory for $package until $($until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)), more than " +
                "90 days out. Read the advisory again when the date comes, and give it a date within 90 days.")
        }
        $reason = $shape.Groups['reason'].Value
        if (@($reason -split '\s+' | Where-Object { $_ -match '[A-Za-z]' }).Count -lt 3) {
            throw ("Line $number of $Path accepts $advisory for $package without saying why. Give the reason it " +
                "doesn't apply to what the bundle does with the library: $text")
        }
        if (-not $seen.Add("$advisory $package")) {
            throw "Line $number of $Path accepts $advisory for $package a second time."
        }
        $entries.Add([pscustomobject]@{
            Advisory = $advisory
            Package  = $package
            Until    = $until
            Reason   = $reason
            Line     = $number
            Expired  = $until -lt $Today
        })
    }
    # No comma wrap: an empty list comes back as nothing, and every caller writes @(...).
    return $entries.ToArray()
}

function Get-Cvss3BaseScore {
    <#
    .SYNOPSIS
        The base score of a CVSS 3.0 or 3.1 vector, or $null for anything else.
    .DESCRIPTION
        The formula and the rounding the CVSS 3.1 specification gives, which 3.0 vectors score the
        same way to one decimal. Temporal and environmental metrics after the base ones are
        ignored, as the base score ignores them. A vector missing a base metric, or with a value
        the specification doesn't define, has no score here rather than a guessed one.
    #>
    param([string]$Vector)

    if ("$Vector" -notmatch '^CVSS:3\.[01]/') { return $null }
    $metrics = @{}
    foreach ($part in @($Vector -split '/' | Select-Object -Skip 1)) {
        $pair = $part -split ':', 2
        if ($pair.Count -eq 2) { $metrics[$pair[0]] = $pair[1] }
    }
    # A metric the vector leaves out reads as '', which neither check below lets through.
    $scopeChanged = $metrics['S'] -ceq 'C'
    if (-not $scopeChanged -and $metrics['S'] -cne 'U') { return $null }
    $weights = @{
        AV = @{ N = 0.85; A = 0.62; L = 0.55; P = 0.2 }
        AC = @{ L = 0.77; H = 0.44 }
        PR = if ($scopeChanged) { @{ N = 0.85; L = 0.68; H = 0.5 } } else { @{ N = 0.85; L = 0.62; H = 0.27 } }
        UI = @{ N = 0.85; R = 0.62 }
        C  = @{ H = 0.56; L = 0.22; N = 0.0 }
        I  = @{ H = 0.56; L = 0.22; N = 0.0 }
        A  = @{ H = 0.56; L = 0.22; N = 0.0 }
    }
    $value = @{}
    foreach ($name in $weights.Keys) {
        $letter = [string]$metrics[$name]
        # Case matters in a vector, and a hashtable's keys don't.
        if ($letter -cnotmatch '^[A-Z]$' -or -not $weights[$name].ContainsKey($letter)) { return $null }
        $value[$name] = [double]$weights[$name][$letter]
    }

    $iss = 1 - ((1 - $value['C']) * (1 - $value['I']) * (1 - $value['A']))
    $impact = if ($scopeChanged) {
        7.52 * ($iss - 0.029) - 3.25 * [Math]::Pow($iss - 0.02, 15)
    } else {
        6.42 * $iss
    }
    if ($impact -le 0) { return 0.0 }
    $exploitability = 8.22 * $value['AV'] * $value['AC'] * $value['PR'] * $value['UI']
    $raw = if ($scopeChanged) { [Math]::Min(1.08 * ($impact + $exploitability), 10) } else {
        [Math]::Min($impact + $exploitability, 10)
    }
    # Roundup as 3.1 defines it: through an integer, so 4.000000001 doesn't become 4.1.
    $scaled = [long][Math]::Round($raw * 100000, [MidpointRounding]::AwayFromZero)
    if ($scaled % 10000 -eq 0) { return $scaled / 100000.0 }
    return ([Math]::Floor($scaled / 10000) + 1) / 10.0
}

function Get-AdvisorySeverity {
    <#
    .SYNOPSIS
        How serious an OSV advisory is: @{ Level; Serious; Why }.
    .DESCRIPTION
        The worse of OSV's own label (database_specific.severity) and the level each CVSS 3 vector
        scores to: CRITICAL from 9.0, HIGH from 7.0, MODERATE from 4.0, LOW above 0. Serious is
        HIGH or CRITICAL, or UNRATED, for an advisory with neither a label nor a vector this can
        score: a CVSS 4 vector alone, say.
    #>
    param([Parameter(Mandatory = $true)]$Advisory)

    $rank = @{ NONE = 0; LOW = 1; MODERATE = 2; HIGH = 3; CRITICAL = 4 }
    $level = $null
    $why = $null
    $label = "$($Advisory.database_specific.severity)".Trim().ToUpperInvariant()
    if ($label -eq 'MEDIUM') { $label = 'MODERATE' }
    if ($label -and $rank.ContainsKey($label)) {
        $level = $label
        $why = "OSV rates it $label"
    }
    foreach ($entry in @($Advisory.severity | Where-Object { $null -ne $_ })) {
        if ("$($entry.type)" -ne 'CVSS_V3') { continue }
        $score = Get-Cvss3BaseScore -Vector ([string]$entry.score)
        if ($null -eq $score) { continue }
        $scored = if ($score -ge 9.0) { 'CRITICAL' } elseif ($score -ge 7.0) { 'HIGH' } elseif ($score -ge 4.0) {
            'MODERATE' } elseif ($score -gt 0) { 'LOW' } else { 'NONE' }
        if ($null -eq $level -or $rank[$scored] -gt $rank[$level]) {
            $level = $scored
            $why = "its CVSS 3 vector scores $score"
        }
    }
    if ($null -eq $level) {
        return [pscustomobject]@{ Level = 'UNRATED'; Serious = $true
            Why = 'OSV gives it no severity this check can read, so it counts as serious until somebody reads it' }
    }
    return [pscustomobject]@{ Level = $level; Serious = $rank[$level] -ge 3; Why = $why }
}

function Invoke-OsvQuery {
    <#
    .SYNOPSIS
        Every advisory OSV has for one package URL, following its pages.
    .DESCRIPTION
        OSV answers {} for a package it knows nothing about, and for a package URL it can't read
        at all, which is why Read-ReleaseSbom refuses a malformed one before it gets here. Anything
        that isn't a query result throws, and so does a failed request: the gate fails closed.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Purl,
        [string]$Endpoint = 'https://api.osv.dev/v1/query'
    )

    $found = New-Object System.Collections.Generic.List[object]
    $token = $null
    for ($page = 0; $page -lt 20; $page++) {
        $query = [ordered]@{ package = [ordered]@{ purl = $Purl } }
        if ($token) { $query['page_token'] = $token }
        try {
            $answer = Invoke-RestMethod -Uri $Endpoint -Method Post -ContentType 'application/json' `
                -Body ($query | ConvertTo-Json -Compress -Depth 4) -TimeoutSec 60
        } catch {
            throw ("OSV could not be asked about ${Purl}: $($_.Exception.Message). The advisory check fails " +
                'closed, so nothing goes out on a check that did not run. Check the connection to api.osv.dev ' +
                'and run it again, or pass -SkipAdvisoryCheck to work offline: the index push asks OSV again.')
        }
        if ($answer -isnot [System.Management.Automation.PSCustomObject]) {
            throw "OSV answered the query about $Purl with something that isn't a query result: $answer"
        }
        # A query result is {} or holds a vulns list, a next_page_token or both, and nothing else.
        # An error that arrives as an object, {"error":"temporarily unavailable"} say, has neither,
        # and read as a package with no advisory it passed the gate on a check that never ran.
        $fields = @($answer.PSObject.Properties | ForEach-Object { $_.Name })
        $odd = if (@($fields | Where-Object { $_ -cnotin @('vulns', 'next_page_token') }).Count -gt 0) {
            'fields a query result does not have'
        } elseif ($fields -ccontains 'vulns' -and $answer.vulns -isnot [array]) {
            'vulns that is not a list'
        } elseif ($fields -ccontains 'next_page_token' -and $answer.next_page_token -isnot [string]) {
            'a next_page_token that is not text'
        }
        if ($odd) {
            $shown = [string]($answer | ConvertTo-Json -Compress -Depth 3 -WarningAction SilentlyContinue)
            if ($shown.Length -gt 200) { $shown = $shown.Substring(0, 200) + '...' }
            throw ("OSV answered the query about $Purl with $odd, which isn't a query result, so nothing " +
                "says the package has no advisory: $shown. The advisory check fails closed. An answer like " +
                'this is OSV reporting trouble, so run the check again in a few minutes.')
        }
        # Assigned, not taken from an if expression: that unrolls the list, and [null] came out as
        # nothing to read.
        $vulns = @()
        if ($fields -ccontains 'vulns') { $vulns = $answer.vulns }
        foreach ($advisory in $vulns) {
            if ($advisory -isnot [System.Management.Automation.PSCustomObject] -or $advisory.id -isnot [string] -or
                    [string]::IsNullOrWhiteSpace($advisory.id)) {
                throw "OSV answered the query about $Purl with an advisory that is not a record or has no id."
            }
            # A withdrawn advisory is one its publisher took back, on a date. ConvertFrom-Json hands
            # that date over as text in Windows PowerShell and as a date in PowerShell 7. Anything
            # else there is not a withdrawal, and taking it for one would drop the advisory.
            $withdrawn = $advisory.PSObject.Properties['withdrawn']
            if ($withdrawn -and $null -ne $withdrawn.Value -and $withdrawn.Value -isnot [string] -and
                    $withdrawn.Value -isnot [datetime]) {
                throw "OSV answered the query about $Purl with $($advisory.id) withdrawn on something that is not a date."
            }
            if ($withdrawn -and $withdrawn.Value) { continue }
            $found.Add($advisory)
        }
        $token = [string]$answer.next_page_token
        if (-not $token) { return $found.ToArray() }
    }
    throw "OSV was still paging its answer about $Purl after 20 pages."
}

function Get-SbomAdvisories {
    <#
    .SYNOPSIS
        One finding per advisory OSV has for a library the SBOM lists.
    .DESCRIPTION
        Each: @{ Package; Version; Purl; Advisory; Aliases; Summary; Severity }. First-party
        components carry no package URL and have nothing to ask about.
    #>
    param([Parameter(Mandatory = $true)]$Sbom)

    $findings = New-Object System.Collections.Generic.List[object]
    foreach ($library in @($Sbom.Libraries)) {
        foreach ($advisory in @(Invoke-OsvQuery -Purl $library.Purl)) {
            $findings.Add([pscustomobject]@{
                Package  = "$($library.Group):$($library.Name)"
                Version  = $library.Version
                Purl     = $library.Purl
                Advisory = [string]$advisory.id
                Aliases  = @($advisory.aliases | Where-Object { $_ } | ForEach-Object { [string]$_ })
                Summary  = "$($advisory.summary)".Trim()
                Severity = Get-AdvisorySeverity -Advisory $advisory
            })
        }
    }
    return $findings.ToArray()
}

function Test-AdvisoryFindings {
    <#
    .SYNOPSIS
        Whether a release may go out with these findings and these exceptions.
    .DESCRIPTION
        Answers @{ Valid; Reason; Refused; Excused; Minor; Stale }, the last four as the lines to
        print. A serious finding needs an exception naming its advisory, or one of the advisory's
        aliases, and its package, dated today or later. An exception that matches no finding at
        all, of any severity, is stale and refused too.
    #>
    param(
        [object[]]$Findings = @(),
        [object[]]$Exceptions = @()
    )

    $findingsIn = @(@($Findings) | Where-Object { $null -ne $_ })
    $exceptionsIn = @(@($Exceptions) | Where-Object { $null -ne $_ })
    $refused = New-Object System.Collections.Generic.List[string]
    $excused = New-Object System.Collections.Generic.List[string]
    $minor = New-Object System.Collections.Generic.List[string]
    # By advisory and package, which the reader holds to one line each.
    $used = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

    foreach ($finding in $findingsIn) {
        $names = @(@($finding.Advisory) + @($finding.Aliases))
        $matching = @($exceptionsIn | Where-Object {
            $exception = $_
            [string]::Equals($exception.Package, $finding.Package, [System.StringComparison]::OrdinalIgnoreCase) -and
                @($names | Where-Object { [string]::Equals($_, $exception.Advisory, [System.StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
        })
        foreach ($exception in $matching) { [void]$used.Add("$($exception.Advisory) $($exception.Package)") }
        $aliases = if (@($finding.Aliases).Count -gt 0) { ", $(@($finding.Aliases) -join ', ')" } else { '' }
        $described = ("$($finding.Advisory) ($($finding.Severity.Level)$aliases) in $($finding.Package) " +
            "$($finding.Version): $($finding.Summary)")
        if (-not $finding.Severity.Serious) {
            $minor.Add("$described; $($finding.Severity.Why)")
            continue
        }
        $current = @($matching | Where-Object { -not $_.Expired })
        if ($current.Count -gt 0) {
            $excused.Add("$described; accepted until $($current[0].Until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)): $($current[0].Reason)")
            continue
        }
        $lapsed = @($matching | Where-Object { $_.Expired })
        if ($lapsed.Count -gt 0) {
            $refused.Add("$described; $($finding.Severity.Why), and its exception ran out on $($lapsed[0].Until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture))")
        } else {
            $refused.Add("$described; $($finding.Severity.Why)")
        }
    }
    $stale = New-Object System.Collections.Generic.List[string]
    foreach ($exception in $exceptionsIn) {
        if (-not $used.Contains("$($exception.Advisory) $($exception.Package)")) {
            $stale.Add("$($exception.Advisory) for $($exception.Package) (line $($exception.Line))")
        }
    }

    $reasons = @()
    if ($refused.Count -gt 0) {
        $reasons += ('OSV reports high or critical advisories for what the bundle carries: ' + ($refused -join '; ') +
            '. Move to a version without them, or accept one in scripts/advisory-exceptions.txt with the reason ' +
            'it does not apply and a date within 90 days.')
    }
    if ($stale.Count -gt 0) {
        $reasons += ('scripts/advisory-exceptions.txt accepts advisories OSV no longer reports for what the bundle ' +
            'carries: ' + ($stale -join '; ') + '. Take them out.')
    }
    return [pscustomobject]@{
        Valid   = $reasons.Count -eq 0
        Reason  = if ($reasons.Count -gt 0) { $reasons -join ' ' } else { $null }
        Refused = $refused.ToArray()
        Excused = $excused.ToArray()
        Minor   = $minor.ToArray()
        Stale   = $stale.ToArray()
    }
}

function Invoke-ReleaseAdvisoryGate {
    <#
    .SYNOPSIS
        Asks OSV about an SBOM's libraries, prints what it said, and throws when the release may
        not go out.
    #>
    param(
        [Parameter(Mandatory = $true)]$Sbom,
        [Parameter(Mandatory = $true)][string]$ExceptionsPath,
        [switch]$SkipAdvisoryCheck,
        [datetime]$Today = [datetime]::Today,
        [string]$ToolAdvisoriesPath
    )

    $libraries = @($Sbom.Libraries)
    $sbomName = Split-Path -Leaf $Sbom.Path
    # Read even when OSV isn't asked: a malformed or overlong exception is wrong offline too.
    $exceptions = @(Read-AdvisoryExceptions -Path $ExceptionsPath -Today $Today)
    # The Gradle that built the release, held to the maintainer advisories recorded for it. It needs
    # no network, so -SkipAdvisoryCheck doesn't skip it, and an SBOM that names no Gradle can't be
    # held to the list at all.
    if (-not $ToolAdvisoriesPath) { $ToolAdvisoriesPath = Join-Path $PSScriptRoot 'build-tool-advisories.txt' }
    $toolRecords = @(Read-BuildToolAdvisories -Path $ToolAdvisoriesPath)
    $gradles = @(@($Sbom.Tools) | Where-Object { $null -ne $_ -and $_.Name -ceq 'gradle' })
    if ($gradles.Count -eq 0) {
        throw "$sbomName names no Gradle under metadata.tools, so the build tool it was built with can't be held to the build tool advisory list."
    }
    foreach ($gradle in $gradles) {
        $toolVerdict = Test-BuildToolAdvisories -Tool $gradle.Name -Version $gradle.Version -Records $toolRecords
        foreach ($line in @($toolVerdict.Minor)) { Write-Host "[advisories] build tool, below high, let through: $line" }
        if (-not $toolVerdict.Valid) { throw "$sbomName records it: $($toolVerdict.Reason)" }
        Write-Host ("[advisories] $sbomName was built with $($gradle.Name) $($gradle.Version), outside the high and " +
            "critical ranges of the $($toolVerdict.Recorded) maintainer advisories recorded for it")
    }
    if ($SkipAdvisoryCheck) {
        Write-Warning ("-SkipAdvisoryCheck: OSV was not asked about the libraries $sbomName lists, so nothing " +
            'here says they have no serious advisory. The index push asks OSV again.')
        return
    }
    if ($libraries.Count -eq 0) {
        Write-Host "[advisories] $sbomName lists no library to ask OSV about"
        return
    }
    $findings = @(Get-SbomAdvisories -Sbom $Sbom)
    $verdict = Test-AdvisoryFindings -Findings $findings -Exceptions $exceptions
    foreach ($line in @($verdict.Minor)) { Write-Host "[advisories] below high, let through: $line" }
    foreach ($line in @($verdict.Excused)) { Write-Host "[advisories] accepted: $line" }
    if (-not $verdict.Valid) { throw $verdict.Reason }
    $packages = @($libraries | ForEach-Object { "$($_.Name) $($_.Version)" }) -join ', '
    $said = if ($findings.Count -eq 0) { 'no advisory' } else { "$($findings.Count) advisories, none refused" }
    Write-Host "[advisories] OSV has $said for the libraries $sbomName lists: $packages"
}

function ConvertTo-BuildToolVersion {
    <#
    .SYNOPSIS
        A released tool version, two or three dotted numbers, as a [version], or $null.
    .DESCRIPTION
        9.8 is 9.8.0. A release candidate, a milestone or a nightly has no place in an advisory's
        ranges that this can work out, so it has no version here and the caller refuses it.
    #>
    param([string]$Text)

    if ("$Text" -notmatch '^\d{1,4}\.\d{1,4}(\.\d{1,4})?$') { return $null }
    $parts = @("$Text".Split('.') | ForEach-Object { [int]$_ })
    if ($parts.Count -eq 2) { $parts += 0 }
    return New-Object System.Version($parts[0], $parts[1], $parts[2])
}

function Read-BuildToolAdvisories {
    <#
    .SYNOPSIS
        The advisories recorded against the tools that run the build, one per line:
        "<advisory> <tool> <severity> <affected> <fixed> <read yyyy-MM-dd>".
    .DESCRIPTION
        A build tool is not a library the build resolves, so no package URL names it and OSV is
        never asked about it: the list is the whole of what the gate knows, kept by hand from the
        maintainer's own advisories. Affected is every range an advisory names, joined by commas,
        "<8.14.6" for all below a version and "9.0.0..9.8.0" for both ends included. Fixed is the
        first release of each line with the fix.

        Read strictly, as the exception lists are. A missing or oversized list, a line in another
        shape, an id that is not a GHSA id, a severity outside the four, a tool this does not
        check, a range or version it cannot place, a fixed version inside its own affected range
        and a record given twice all stop the run, naming the line.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The build tool advisory list is missing: $Path"
    }
    if ((Get-Item -LiteralPath $Path).Length -gt 65536) {
        throw "The build tool advisory list is over 64 KB, which is not a list this check reads: $Path"
    }
    $records = New-Object System.Collections.Generic.List[object]
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $number = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $number++
        $text = ([string]$line).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $shape = [regex]::Match($text,
            '^(?<id>GHSA(-[23456789cfghjmpqrvwx]{4}){3}) (?<tool>\S+) (?<severity>\S+) (?<affected>\S+) (?<fixed>\S+) (?<read>\S+)$')
        if (-not $shape.Success) {
            throw ("Line $number of $Path is not ""<advisory> <tool> <severity> <affected> <fixed> <read yyyy-MM-dd>"" " +
                "with a GHSA id: $text")
        }
        $advisory = $shape.Groups['id'].Value
        $tool = $shape.Groups['tool'].Value
        if ($tool -cne 'gradle') {
            throw "Line $number of $Path names the tool $tool, which this check does not read a version for: $text"
        }
        $severity = $shape.Groups['severity'].Value
        if ($severity -cnotin @('LOW', 'MODERATE', 'HIGH', 'CRITICAL')) {
            throw "Line $number of $Path rates $advisory $severity, which is not LOW, MODERATE, HIGH or CRITICAL: $text"
        }
        $ranges = @(foreach ($range in $shape.Groups['affected'].Value.Split(',')) {
            # The versions are held to their own shape below, so these only find the two ends.
            $below = [regex]::Match($range, '^<(?<end>.+)$')
            $between = [regex]::Match($range, '^(?<start>.+?)\.\.(?<end>.+)$')
            $start = if ($between.Success) { ConvertTo-BuildToolVersion $between.Groups['start'].Value } else { $null }
            $end = if ($below.Success) { ConvertTo-BuildToolVersion $below.Groups['end'].Value } elseif ($between.Success) {
                ConvertTo-BuildToolVersion $between.Groups['end'].Value } else { $null }
            if ($null -eq $end -or ($between.Success -and ($null -eq $start -or $start -gt $end))) {
                throw ("Line $number of $Path gives $advisory the affected range $range, which is neither " +
                    """<X"" nor ""X..Y"" with released versions in order: $text")
            }
            [pscustomobject]@{ Text = $range; Start = $start; End = $end; EndIncluded = $between.Success }
        })
        $fixed = @(foreach ($release in $shape.Groups['fixed'].Value.Split(',')) {
            $version = ConvertTo-BuildToolVersion $release
            if ($null -eq $version) {
                throw "Line $number of $Path gives $advisory the fixed version $release, which is not a released version: $text"
            }
            if (@($ranges | Where-Object { Test-BuildToolRange -Range $_ -Version $version }).Count -gt 0) {
                throw "Line $number of $Path calls $release fixed for $advisory, inside the range it calls affected: $text"
            }
            $release
        })
        $read = [datetime]::MinValue
        if (-not [datetime]::TryParseExact($shape.Groups['read'].Value, 'yyyy-MM-dd',
                [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$read)) {
            throw "Line $number of $Path says $advisory was read on $($shape.Groups['read'].Value), which isn't a yyyy-MM-dd date: $text"
        }
        if (-not $seen.Add("$advisory $tool")) {
            throw "Line $number of $Path records $advisory for $tool a second time."
        }
        $records.Add([pscustomobject]@{
            Advisory = $advisory
            Tool     = $tool
            Severity = $severity
            Affected = $ranges
            Fixed    = $fixed
            Read     = $read
            Line     = $number
        })
    }
    return $records.ToArray()
}

function Test-BuildToolRange {
    # Whether a version is inside one affected range Read-BuildToolAdvisories read.
    param([Parameter(Mandatory = $true)]$Range, [Parameter(Mandatory = $true)][version]$Version)

    if ($null -ne $Range.Start -and $Version -lt $Range.Start) { return $false }
    if ($Range.EndIncluded) { return $Version -le $Range.End }
    return $Version -lt $Range.End
}

function Test-BuildToolAdvisories {
    <#
    .SYNOPSIS
        Whether a build may run on this version of a tool: @{ Valid; Reason; Refused; Minor;
        Recorded; LastRead }.
    .DESCRIPTION
        Refused and Minor are the lines to print, for the recorded advisories whose ranges hold
        the version: high and critical ones refuse it, as they do a library, and the rest are let
        through. Recorded counts the advisories the list holds for the tool and LastRead is the
        earliest day any of them was read, which is how old the answer may be.

        A version this cannot place, and a tool the list records nothing for, throw instead of
        answering: neither is a version known to be outside every range.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Tool,
        [string]$Version,
        [object[]]$Records = @()
    )

    $placed = ConvertTo-BuildToolVersion $Version
    if ($null -eq $placed) {
        throw ("The build ran on $Tool '$Version', which is not a released version this check can place in " +
            'an advisory range. Build on a released version.')
    }
    $mine = @(@($Records) | Where-Object { $null -ne $_ -and $_.Tool -ceq $Tool })
    if ($mine.Count -eq 0) {
        throw "The build tool advisory list records nothing for $Tool, so nothing says $Tool $Version is clear of its advisories."
    }
    $refused = New-Object System.Collections.Generic.List[string]
    $minor = New-Object System.Collections.Generic.List[string]
    foreach ($record in $mine) {
        if (@($record.Affected | Where-Object { Test-BuildToolRange -Range $_ -Version $placed }).Count -eq 0) { continue }
        $described = ("$($record.Advisory) ($($record.Severity)) covers $Tool " +
            "$(@($record.Affected | ForEach-Object { $_.Text }) -join ', ') and is fixed in $($record.Fixed -join ' and ')")
        if ($record.Severity -cin @('HIGH', 'CRITICAL')) { $refused.Add($described) } else { $minor.Add($described) }
    }
    $reason = $null
    if ($refused.Count -gt 0) {
        $reason = ("The build ran on $Tool $Version, which its maintainer's high or critical advisories cover: " +
            ($refused -join '; ') + '. Move the build to a fixed version. This is the tool that runs the ' +
            'build, not a library it resolves.')
    }
    return [pscustomobject]@{
        Valid    = $refused.Count -eq 0
        Reason   = $reason
        Refused  = $refused.ToArray()
        Minor    = $minor.ToArray()
        Recorded = $mine.Count
        LastRead = @($mine | ForEach-Object { $_.Read } | Sort-Object)[0]
    }
}

<# Live host ownership checks for the native navigation probe. No device commands run here. #>
function Start-NavigationLeaseServer {
    param($Session)
    Assert-NavigationLease $Session
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $control = [hashtable]::Synchronized(@{ Stop = $false })
    $context = [pscustomobject]@{ Lease = $Session.Lease; Serial = $Session.Serial; Chat = $Session.Chat;
        Token = $Session.Token; Expires = $null }
    $capability = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $worker = [PowerShell]::Create()
    try {
        [void]$worker.AddScript({
            param($Listener, $Control, $Context, $Capability, $LeaseCheck)
            $ErrorActionPreference = 'Stop'
            Set-Item -Path Function:Assert-NavigationLease -Value ([scriptblock]::Create($LeaseCheck))
            try {
                while (-not $Control.Stop) {
                    if (-not $Listener.Pending()) { Start-Sleep -Milliseconds 5; continue }
                    $client = $Listener.AcceptTcpClient()
                    try {
                        $stream = $client.GetStream(); $stream.ReadTimeout = 1000; $stream.WriteTimeout = 1000
                        $request = [Text.StringBuilder]::new()
                        $readClock = [Diagnostics.Stopwatch]::StartNew()
                        while ($request.Length -le 64) {
                            $remaining = 1000 - [int]$readClock.ElapsedMilliseconds
                            if ($remaining -le 0) { throw [IO.IOException]::new('Lease request timed out.') }
                            $stream.ReadTimeout = $remaining
                            $value = $stream.ReadByte()
                            if ($value -eq 10 -or $value -lt 0) { break }
                            [void]$request.Append([char]$value)
                        }
                        $allowed = $false
                        if ($value -eq 10 -and $request.ToString() -ceq $Capability -and -not $Control.Stop) {
                            try { Assert-NavigationLease $Context; $allowed = $true } catch { }
                        }
                        # One byte is enough. Never return lease contents or ownership tokens.
                        $stream.WriteByte([byte]$(if ($allowed) { 1 } else { 0 }))
                    } catch [IO.IOException] {
                        # A disconnected or stalled request grants nothing. Continue serving this run.
                    } finally { $client.Dispose() }
                }
            } finally { $Listener.Stop() }
        }).AddArgument($listener).AddArgument($control).AddArgument($context).AddArgument($capability).
            AddArgument(${function:Assert-NavigationLease}.ToString())
        $pending = $worker.BeginInvoke()
        return [pscustomobject]@{ Listener = $listener; Control = $control; Worker = $worker; Pending = $pending;
            Port = $listener.LocalEndpoint.Port; Capability = $capability;
            Socket = ('hushtelegram-nav-' + [guid]::NewGuid().ToString('N')) }
    } catch { $listener.Stop(); $worker.Dispose(); throw }
}

function Stop-NavigationLeaseServer {
    param($Server)
    if (-not $Server) { return }
    $Server.Control.Stop = $true
    try {
        if (-not $Server.Pending.AsyncWaitHandle.WaitOne(2500)) { $Server.Worker.Stop() }
        [void]$Server.Worker.EndInvoke($Server.Pending)
        if ($Server.Worker.Streams.Error.Count) { throw [InvalidOperationException]::new('Native lease ownership server failed.', $Server.Worker.Streams.Error[0].Exception) }
    } finally { $Server.Listener.Stop(); $Server.Worker.Dispose() }
}

function Remove-NavigationLeaseReverse {
    param($Session, $State)
    if (-not $State.LeaseSocket -and -not $State.LeasePort) { return }
    if ($State.LeaseSocket -cnotmatch '^hushtelegram-nav-[0-9a-f]{32}$' -or
        ($State.LeasePort -isnot [int] -and $State.LeasePort -isnot [long]) -or $State.LeasePort -lt 1 -or $State.LeasePort -gt 65535) {
        throw 'Invalid navigation recovery lease socket.'
    }
    $remote = 'localabstract:' + $State.LeaseSocket
    $rows = @(Invoke-NavigationAdb $Session @('reverse', '--list') | Where-Object {
        $parts = @(([string]$_).Trim() -split '\s+')
        $parts.Count -ge 2 -and $parts[1] -ceq $remote
    })
    if (-not $rows.Count) { return }
    $parts = @(([string]$rows[0]).Trim() -split '\s+')
    if ($rows.Count -ne 1 -or $parts.Count -ne 3 -or $parts[2] -cne "tcp:$($State.LeasePort)") {
        throw 'The recovery socket mapping belongs to another endpoint.'
    }
    [void](Invoke-NavigationAdb $Session @('reverse', '--remove', $remote))
}

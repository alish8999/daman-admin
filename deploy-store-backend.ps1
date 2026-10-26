<#
.SYNOPSIS
    Build daman-backend once and release it to EVERY hosted store.

.DESCRIPTION
    All hosted stores run the same jar, /root/daman-store.jar, and differ only by the environment
    variables in their own systemd unit. This script builds daman-backend's jar locally (JDK 21
    explicitly - IntelliJ's bundled JDK 25 silently breaks Lombok), scp's it to the server as the
    shared jar, then calls POST /api/hosted-stores/restart-all on the server's own admin-backend.

    The unit files are NOT written here: the provisioning endpoint owns them.

    The restart is sequential with a health check between stores and HALTS at the first store that
    does not come back healthy. That delays and sequences the exposure to a bad jar; it does NOT
    contain it: the new jar is already on disk before the walk starts and every unit has
    Restart=always, so a store the walk has not reached will load it on any restart, and the health
    check only catches "does not boot". The previous jar is kept as /root/daman-store.jar.prev for a
    manual rollback (nothing rolls back automatically). Stores are walked in id order, so the
    oldest store is always the canary. The jar is uploaded to a temp name and renamed into place,
    so a running JVM never has its jar rewritten underneath it.

    viora is an ordinary store here since 2026-10-26. It used to run its own frozen jar
    (/root/daman-store-viora.jar) and was excluded by the server, which made it the one store a
    release could not reach - and so the only one that never received a backend fix. Its unit now
    points at the shared jar. It keeps its legacy data directory (/root/.daman/viora), because its
    unit carries no -Duser.home, so its unit must never be re-rendered by StoreUnitRenderer; that
    is guarded by `viora` being a reserved client code. Its old jar is kept for rollback.

    viora's registry row is the oldest, so viora is now the canary of every release.

    The jar is a GENERIC build (client-meta.properties must say client.generic=true - this script
    refuses to ship anything else).

.PARAMETER AdminToken
    Admin session Bearer token (the one from an admin-portal login). Falls back to the
    DAMAN_ADMIN_TOKEN environment variable. The restart route is admin-token-gated.
#>

param(
    [string]$AdminToken = $env:DAMAN_ADMIN_TOKEN
)

$ErrorActionPreference = "Stop"

$JavaHome    = "C:\Users\ASUS\.jdks\ms-21.0.10"
$BackendDir  = "D:\Daman\src\daman-backend"
$PomPath     = Join-Path $BackendDir "pom.xml"
$MetaPath    = Join-Path $BackendDir "src\main\resources\client-meta.properties"
$Server      = "root@162.55.51.189"
$RemoteJar   = "/root/daman-store.jar"   # shared by every hosted store (StoreUnitRenderer.SHARED_JAR)

if (-not $AdminToken) { throw "No admin token. Pass -AdminToken or set DAMAN_ADMIN_TOKEN (paste the Bearer token from an admin-portal login) - the restart-all route is token-gated." }

$utf8NoBom = New-Object System.Text.UTF8Encoding $false

# Runs one curl on the SERVER against its own admin-backend (localhost:8083, not the public hostname:
# a walk of many stores outlasts Cloudflare's ~100 s proxy timeout). Returns the output lines; the
# last line is the HTTP status. The command goes up as an LF-only FILE, not piped over ssh stdin:
# Windows PowerShell 5.1 terminates a piped string with CRLF, and a stray CR would end up inside
# curl's URL (same trap the old unit-writing code avoided for systemd).
function Invoke-ServerCurl([string]$Method, [string]$UrlPath) {
    # $AdminToken is interpolated into a SINGLE-QUOTED shell string, so a token containing a
    # straight quote would break out of it and mangle the command. Admin tokens are UUIDs
    # (SessionService) so this cannot happen today - but if the token format ever changes, quote it
    # properly here rather than discovering it as a baffling curl error.
    $cmd = "curl -s -w '\n%{http_code}' -X $Method -H 'Authorization: Bearer $AdminToken' --max-time 3600 http://localhost:8083$UrlPath`n"
    $local = Join-Path $env:TEMP "deploy-store-curl.sh"
    [System.IO.File]::WriteAllText($local, $cmd, $utf8NoBom)
    try {
        # The script holds an admin Bearer token that can provision infrastructure, so it is never
        # world-readable on the server: pre-created root-only under umask 077 so scp writes INTO a
        # 600 file rather than creating a 644 one, and chmodded again before it is read in case a
        # future scp/sftp sets the mode itself. On a root-only box this is defence in depth.
        ssh $Server "umask 077; : > /tmp/deploy-store-curl.sh; chmod 600 /tmp/deploy-store-curl.sh"
        if ($LASTEXITCODE -ne 0) { throw "could not pre-create the remote curl script root-only." }
        scp $local "${Server}:/tmp/deploy-store-curl.sh"
        if ($LASTEXITCODE -ne 0) { throw "scp of the curl script failed." }
        $out = ssh $Server "chmod 600 /tmp/deploy-store-curl.sh; bash /tmp/deploy-store-curl.sh; RC=`$?; rm -f /tmp/deploy-store-curl.sh; exit `$RC"
        if ($LASTEXITCODE -ne 0) { throw "ssh/curl to the server's admin-backend failed." }
        return @($out)
    } finally { Remove-Item $local -ErrorAction SilentlyContinue }
}

# The Maven wrapper's cached mvn.cmd folder hash has moved between sessions before -
# discover it instead of hardcoding it.
$MvnCmd = Get-ChildItem -Path "C:\Users\ASUS\.m2\wrapper\dists" -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName   # highest version dir wins (3.9.x > 3.8.x)
if (-not $MvnCmd) { throw "No mvn.cmd found under C:\Users\ASUS\.m2\wrapper\dists - run a Maven build once in IntelliJ to populate it." }

Write-Host "==> [1/5] Verifying this is a generic build..." -ForegroundColor Cyan
if ((Get-Content $MetaPath -Raw) -notmatch '(?m)^\s*client\.generic\s*=\s*true\s*$') {
    throw "client-meta.properties is not client.generic=true (a per-client dev-run may have left it). Run 'Reset dev' in the admin panel, then retry."
}
if ((Get-Content $MetaPath -Raw) -match '(?m)^\s*client\.code\s*=\s*\S') {
    throw "client-meta.properties has a client.code set - refusing to ship a per-client build as a hosted generic instance."
}

Write-Host "==> [2/5] Checking the admin token against the server (before anything is changed)..." -ForegroundColor Cyan
$pre = Invoke-ServerCurl "GET" "/api/hosted-stores"
if ($pre[-1] -eq "401") { throw "The server rejected the admin token (HTTP 401) - it is stale or wrong. Log in to the admin portal again and pass a fresh -AdminToken. Nothing was built or changed." }
if ($pre[-1] -ne "200") { throw "Preflight GET /api/hosted-stores returned HTTP $($pre[-1]) - expected 200. Nothing was built or changed." }

Write-Host "==> [3/5] Building daman-backend (JDK 21)..." -ForegroundColor Cyan
$env:JAVA_HOME = $JavaHome
& $MvnCmd -q -o clean package "-DskipTests" -f $PomPath
if ($LASTEXITCODE -ne 0) { throw "Build failed - see Maven output above (a leftover java process holding target\*.jar also causes this)." }
$JarPath = Get-ChildItem -Path (Join-Path $BackendDir "target") -Filter "daman-backend-*.jar" |
    Where-Object { $_.Name -notmatch '\.original$|sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $JarPath) { throw "Built jar not found under $BackendDir\target." }
Write-Host "    Built: $JarPath" -ForegroundColor DarkGray

Write-Host "==> [4/5] Uploading jar to ${Server}:$RemoteJar ..." -ForegroundColor Cyan
# Upload beside the live jar, then rename: rename is atomic, so no store ever reads a half-written jar
# and a running JVM keeps its old inode until its own restart.
scp $JarPath "${Server}:${RemoteJar}.new"
if ($LASTEXITCODE -ne 0) { throw "scp of the jar failed." }
# Keep the outgoing jar for a MANUAL rollback (nothing rolls back automatically). The cp failing
# (no live jar yet) is tolerated so the mv below still runs.
ssh $Server "cp -p ${RemoteJar} ${RemoteJar}.prev 2>/dev/null; mv -f ${RemoteJar}.new ${RemoteJar}"
if ($LASTEXITCODE -ne 0) { throw "Could not move the uploaded jar into place at $RemoteJar." }

Write-Host "==> [5/5] Restarting every hosted store, one at a time (halts at the first unhealthy one)..." -ForegroundColor Cyan
Write-Host "    Stores are walked in id order: the oldest store is the canary." -ForegroundColor DarkGray
$lines = Invoke-ServerCurl "POST" "/api/hosted-stores/restart-all"
$httpCode = $lines[-1]
$body = if ($lines.Count -gt 1) { ($lines[0..($lines.Count - 2)] -join "`n") } else { "" }
$report = $null
try { $report = $body | ConvertFrom-Json } catch { }
# The route answers {walked:[...], skipped:[...]}. Both halves matter: an EMPTY walked list means
# the release restarted nothing at all, which this script used to report as a complete success.
$walked = @()
$skipped = @()
if ($report) {
    if ($null -ne $report.walked) { $walked = @($report.walked) }
    if ($null -ne $report.skipped) { $skipped = @($report.skipped) }
}
foreach ($r in $walked) {
    $ok = $r.restarted -and $r.healthy
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host ("    {0,-24} restarted={1,-5} healthy={2} {3}" -f $r.clientCode, $r.restarted, $r.healthy, $r.error) -ForegroundColor $color
}
foreach ($s in $skipped) {
    Write-Host ("    {0,-24} SKIPPED: {1}" -f $s.clientCode, $s.reason) -ForegroundColor DarkGray
}
if ($httpCode -eq "200") {
    Write-Host ""
    if ($walked.Count -eq 0) {
        # Not a success. Every store keeps running the OLD jar in memory while the NEW jar sits at
        # $RemoteJar, and Restart=always will load it at some unpredictable later moment.
        Write-Host "!!! WARNING: the new jar is in place at $RemoteJar but NOT ONE STORE WAS RESTARTED." -ForegroundColor Yellow
        if ($skipped.Count -eq 0) {
            Write-Host "    The registry is EMPTY - the server has no hosted_instances rows at all." -ForegroundColor Yellow
        } else {
            Write-Host "    Every registry row was skipped (reasons above). A store stuck at FAILED while its" -ForegroundColor Yellow
            Write-Host "    unit runs fine looks exactly like this: fix its row, then re-run the release." -ForegroundColor Yellow
        }
        Write-Host "    Every store is still running the OLD jar from memory and will pick the new one up on" -ForegroundColor Yellow
        Write-Host "    its next restart, unsupervised. Restart them by hand or fix the registry and re-run." -ForegroundColor Yellow
    } else {
        Write-Host ("==> Release complete. {0} hosted store(s) restarted healthy on {1}{2}." -f `
            $walked.Count, $RemoteJar, $(if ($skipped.Count -gt 0) { " ($($skipped.Count) skipped, see above)" } else { "" })) -ForegroundColor Green
    }
} else {
    Write-Host $body
    throw "Release HALTED (HTTP $httpCode): a store did not come back healthy. Stores after it were NOT restarted, but the new jar is already in place at $RemoteJar and Restart=always will load it on any restart. Manual rollback: ssh $Server 'mv -f $RemoteJar.prev $RemoteJar', then systemctl restart the halted store (see its journalctl -u <service>)."
}

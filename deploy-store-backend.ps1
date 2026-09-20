<#
.SYNOPSIS
    Build + deploy a hosted store's daman-backend instance to the Hetzner server.

.DESCRIPTION
    Builds daman-backend's jar locally (JDK 21 explicitly - IntelliJ's bundled JDK 25 silently
    breaks Lombok), scp's it to the server as /root/daman-store-<ClientCode>.jar, writes an
    idempotent per-store systemd unit, restarts it, and verifies /api/health.

    The jar is a GENERIC build (client-meta.properties must say client.generic=true - this script
    refuses to ship anything else). The store's identity comes only from the DAMAN_CLIENT_CODE
    env var in the unit and from the licence activated against this instance's machine ID.
    Data lives in /root/.daman/<ClientCode>/ (the desktop profile's default: ~/.daman/<code>/).

    NOTE: the licence file is ~/.daman/license.dat (one per OS user), so this layout supports ONE
    hosted store per server user. A second hosted store needs its own OS user / user.home.

.PARAMETER ClientCode
    Store client code, e.g. "viora".
.PARAMETER Port
    Local port for this store's backend (proxied by the Cloudflare Tunnel), e.g. 8090.
#>

param(
    [Parameter(Mandatory = $true)][string]$ClientCode,
    [Parameter(Mandatory = $true)][int]$Port
)

$ErrorActionPreference = "Stop"

$JavaHome    = "C:\Users\ASUS\.jdks\ms-21.0.10"
$BackendDir  = "D:\Daman\src\daman-backend"
$PomPath     = Join-Path $BackendDir "pom.xml"
$MetaPath    = Join-Path $BackendDir "src\main\resources\client-meta.properties"
$Server      = "root@162.55.51.189"
$ServiceName = "daman-store-$ClientCode"
$RemoteJar   = "/root/$ServiceName.jar"

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

Write-Host "==> [2/5] Building daman-backend (JDK 21)..." -ForegroundColor Cyan
$env:JAVA_HOME = $JavaHome
& $MvnCmd -q -o clean package "-DskipTests" -f $PomPath
if ($LASTEXITCODE -ne 0) { throw "Build failed - see Maven output above (a leftover java process holding target\*.jar also causes this)." }
$JarPath = Get-ChildItem -Path (Join-Path $BackendDir "target") -Filter "daman-backend-*.jar" |
    Where-Object { $_.Name -notmatch '\.original$|sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $JarPath) { throw "Built jar not found under $BackendDir\target." }
Write-Host "    Built: $JarPath" -ForegroundColor DarkGray

Write-Host "==> [3/5] Uploading jar to ${Server}:$RemoteJar ..." -ForegroundColor Cyan
scp $JarPath "${Server}:${RemoteJar}"
if ($LASTEXITCODE -ne 0) { throw "scp of the jar failed." }

Write-Host "==> [4/5] Writing systemd unit ($ServiceName)..." -ForegroundColor Cyan
# Real file + forced LF endings (same reason as deploy-admin-backend.ps1: a stray \r breaks bash/systemd
# when a multi-line string is passed as an ssh argument).
$unit = @"
[Unit]
Description=Daman Store Backend ($ClientCode)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root
Environment=DAMAN_PORT=$Port
Environment=DAMAN_CLIENT_CODE=$ClientCode
Environment=SPRING_PROFILES_ACTIVE=desktop
ExecStart=/usr/bin/java -Xmx768m -jar $RemoteJar
# "always", not "on-failure": Settings -> Restore DB (and other in-app restarts) make the backend exit
# with status 0 on purpose, expecting Electron's shell to relaunch it. on-failure treats a clean exit
# as "done" and leaves the service dead. A manual 'systemctl stop' is still respected.
Restart=always
RestartSec=5
StandardOutput=append:/var/log/$ServiceName.log
StandardError=append:/var/log/$ServiceName.log

[Install]
WantedBy=multi-user.target
"@
$unit = $unit -replace "`r`n", "`n"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$localUnit = Join-Path $env:TEMP "$ServiceName.service"
[System.IO.File]::WriteAllText($localUnit, $unit, $utf8NoBom)
scp $localUnit "${Server}:/etc/systemd/system/$ServiceName.service"
if ($LASTEXITCODE -ne 0) { throw "scp of the systemd unit failed." }
Remove-Item $localUnit -ErrorAction SilentlyContinue

Write-Host "==> [5/5] Restarting and verifying /api/health on :$Port ..." -ForegroundColor Cyan
$remote = @'
systemctl daemon-reload
systemctl enable __SVC__
systemctl restart __SVC__
CODE=000
for i in $(seq 1 20); do
  sleep 3
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:__PORT__/api/health)
  if [ "$CODE" = "200" ]; then break; fi
done
echo "HTTP $CODE"
if [ "$CODE" != "200" ]; then echo "--- last log lines ---"; tail -30 /var/log/__SVC__.log; exit 1; fi
'@
$remote = ($remote -replace "`r`n", "`n") -replace "__SVC__", $ServiceName -replace "__PORT__", $Port
$localVerify = Join-Path $env:TEMP "deploy-store-verify.sh"
[System.IO.File]::WriteAllText($localVerify, $remote, $utf8NoBom)
scp $localVerify "${Server}:/tmp/deploy-store-verify.sh"
if ($LASTEXITCODE -ne 0) { throw "scp of the verify script failed." }
Remove-Item $localVerify -ErrorAction SilentlyContinue

ssh $Server "bash /tmp/deploy-store-verify.sh; RC=`$?; rm -f /tmp/deploy-store-verify.sh; exit `$RC"
if ($LASTEXITCODE -ne 0) { throw "Remote restart/verify failed - ssh in and check 'systemctl status $ServiceName' / 'journalctl -u $ServiceName'." }

Write-Host ""
Write-Host "==> Deploy complete. $ServiceName is up on port $Port." -ForegroundColor Green

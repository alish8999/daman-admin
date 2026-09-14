<#
.SYNOPSIS
    One-command build + deploy for daman-admin-backend to the hosted Hetzner server.

.DESCRIPTION
    Builds the jar locally (JDK 21 explicitly, per Docs/CLAUDE.md's Lombok/JBR-25 caution),
    scp's it to the server, restarts the systemd service, and verifies the deploy by checking
    for the "License RSA key pair loaded from" log line and a 200 from the public-key endpoint.
    The health check retries for up to ~18 seconds since Spring Boot doesn't always finish
    starting within a few seconds of a restart.

    Uses SSH key auth - runs unattended, no password prompts. The key (~/.ssh/daman_admin_deploy)
    is wired to this host by a "Host 162.55.51.189" stanza in ~/.ssh/config, so the plain scp/ssh
    calls below pick it up with no extra flags. Update $Server below if the host ever changes
    (and add a matching ~/.ssh/config stanza for the new host).

.NOTES
    The Maven wrapper's cached mvn.cmd path (below) has moved between sessions before, per
    Docs/CLAUDE.md. If this script fails at the build step with a "not found" error, re-find it:
        Get-ChildItem -Path "C:\Users\ASUS\.m2\wrapper\dists" -Recurse -Filter "mvn.cmd"
    and update $MvnCmd below.
#>

$ErrorActionPreference = "Stop"

$JavaHome      = "C:\Users\ASUS\.jdks\ms-21.0.10"
$MvnCmd        = "C:\Users\ASUS\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd"
$PomPath       = "D:\Daman\src\daman-admin\daman-admin-backend\pom.xml"
$JarPath       = "D:\Daman\src\daman-admin\daman-admin-backend\target\daman-admin-backend-0.0.1-SNAPSHOT.jar"
$Server        = "root@162.55.51.189"
$RemoteJarPath = "/root/daman-admin-backend.jar"

Write-Host "==> [1/3] Building daman-admin-backend (JDK 21)..." -ForegroundColor Cyan
$env:JAVA_HOME = $JavaHome
& $MvnCmd -q -o clean package -DskipTests -f $PomPath
if ($LASTEXITCODE -ne 0) { throw "Build failed - see Maven output above." }

if (-not (Test-Path $JarPath)) { throw "Expected jar not found at $JarPath after build." }
Write-Host "    Built: $JarPath" -ForegroundColor DarkGray

Write-Host "==> [2/3] Uploading jar to $Server (SSH key auth)..." -ForegroundColor Cyan
scp $JarPath "${Server}:${RemoteJarPath}"
if ($LASTEXITCODE -ne 0) { throw "scp upload failed." }

Write-Host "==> [3/3] Restarting service and verifying (SSH key auth)..." -ForegroundColor Cyan

# Single-quoted here-string: passed to ssh completely literally, so bash on the
# remote end expands its own $CODE/$i - PowerShell must NOT touch them here.
$remoteScript = @'
systemctl restart daman-admin-backend
CODE=000
for i in 1 2 3 4 5 6; do
  sleep 3
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/api/licenses/public-key)
  if [ "$CODE" = "200" ]; then break; fi
done
echo ---key-check---
grep "License RSA key pair" /var/log/daman-admin-backend.log | tail -1
echo ---health-check---
echo "HTTP $CODE"
if [ "$CODE" != "200" ]; then exit 1; fi
'@

ssh $Server $remoteScript
if ($LASTEXITCODE -ne 0) { throw "Remote restart/verify command failed - SSH in manually and check 'systemctl status daman-admin-backend'." }

Write-Host ""
Write-Host "==> Deploy complete." -ForegroundColor Green

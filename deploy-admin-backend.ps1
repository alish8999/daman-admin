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
# Uploaded to a SIDE path and moved into place only once it has arrived whole.
#
# scp used to write straight over $RemoteJarPath, which meant an interrupted upload destroyed the
# running deployment: the unit is Restart=always, so systemd then crash-looped on a truncated jar.
# That happened on 2026-10-31 -- the transfer stopped 103 bytes short, and because a zip's central
# directory lives at the END of the file, the Spring Boot launcher started but could not find a
# single class: NoClassDefFoundError: org/springframework/boot/SpringApplication, 57 restarts, the
# admin API down. A partial upload must cost nothing, so it lands beside the live jar instead.
$RemoteTmpPath = "$RemoteJarPath.uploading"
scp $JarPath "${Server}:${RemoteTmpPath}"
if ($LASTEXITCODE -ne 0) { throw "scp upload failed - the live jar is untouched, the service is still running." }

# Verify the bytes actually all arrived before anything replaces the working jar. scp can exit 0 on
# a connection that dropped at the right moment; the size is the thing that proves it.
$LocalSize = (Get-Item $JarPath).Length
Write-Host "    Verifying upload ($LocalSize bytes)..." -ForegroundColor DarkGray
$RemoteSize = (ssh $Server "stat -c %s '$RemoteTmpPath'").Trim()
if ($LASTEXITCODE -ne 0) { throw "Could not stat the uploaded jar on the server." }
if ("$RemoteSize" -ne "$LocalSize") {
    ssh $Server "rm -f '$RemoteTmpPath'" | Out-Null
    throw "Upload incomplete: $RemoteSize of $LocalSize bytes arrived. The live jar is untouched; re-run this script."
}

# Atomic within the filesystem, so the service can never see a half-written jar.
ssh $Server "mv -f '$RemoteTmpPath' '$RemoteJarPath'"
if ($LASTEXITCODE -ne 0) { throw "Could not move the uploaded jar into place." }
Write-Host "    Uploaded and moved into place." -ForegroundColor DarkGray

Write-Host "==> [3/3] Restarting service and verifying (SSH key auth)..." -ForegroundColor Cyan

# Single-quoted here-string: literal, so bash on the remote end expands its own
# $CODE/$i - PowerShell must NOT touch them here. Written to a real file (not
# passed as a giant ssh command-line argument) with forced LF line endings,
# because passing a multi-line string directly as an ssh argument is fragile
# when this script is spawned without a real console attached (e.g. from the
# deploy dashboard's child_process) - a stray \r has been observed sneaking in
# under that invocation path and breaking bash on the remote end (systemd unit
# name comes out as "daman-admin-backend\r").
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
$remoteScript = $remoteScript -replace "`r`n", "`n"

$localScriptPath = Join-Path $env:TEMP "deploy-admin-backend-verify.sh"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($localScriptPath, $remoteScript, $utf8NoBom)

scp $localScriptPath "${Server}:/tmp/deploy-admin-backend-verify.sh"
if ($LASTEXITCODE -ne 0) { throw "scp of the remote verify script failed." }
Remove-Item $localScriptPath -ErrorAction SilentlyContinue

ssh $Server "bash /tmp/deploy-admin-backend-verify.sh; RC=`$?; rm -f /tmp/deploy-admin-backend-verify.sh; exit `$RC"
if ($LASTEXITCODE -ne 0) { throw "Remote restart/verify command failed - SSH in manually and check 'systemctl status daman-admin-backend'." }

Write-Host ""
Write-Host "==> Deploy complete." -ForegroundColor Green

<#
.SYNOPSIS
    Deploy a newly-built Android APK to R2 and update the website's download links.

.DESCRIPTION
    The phone till's counterpart to deploy-generic-release.ps1, and deliberately the
    same shape: find the built artifact, upload it to the daman-installers R2 bucket
    (--remote, not the local simulated one), verify it is actually live at
    dl.damansoft.com with a matching file size, rewrite daman-website/index.html's
    Android links, then commit and push - daman-website deploys via GitHub Pages, so
    the push is what makes the new version downloadable.

    One step has no equivalent on the Windows side and it is the important one:
    SIGNATURE VERIFICATION. Gradle's assembleRelease succeeds whether or not the
    signing properties exist; without them it produces app-release-unsigned.apk,
    which Android refuses to install with a message that tells the shop nothing.
    That file is useful for proving the pipeline and must never reach the website,
    so this script runs apksigner and refuses anything it will not vouch for. The
    cost of being wrong is a dead download link that looks like a broken app.

    There is no latest.yml / .blockmap equivalent here. The phone app has no
    auto-updater - a new version is the same link, sent again - which is why
    /api/pos/handshake reports minAppVersion instead. See
    Docs/superpowers/runbooks/android-build.md.

.PARAMETER Version
    The version string exactly as it appears in the built filename, e.g. "1.5.1"
    (matches Daman-Accounting_1.5.1_generic.apk).

.PARAMETER SkipWebsite
    Upload and verify, but leave index.html alone and push nothing. For putting a
    build somewhere a tester can reach without announcing it to every visitor.

.EXAMPLE
    .\deploy-mobile-release.ps1 -Version 1.5.1
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [switch]$SkipWebsite
)

$ErrorActionPreference = "Stop"

$BuildDir     = "D:\Daman\src\clients-build\generic"
$WebsiteIndex = "D:\Daman\src\daman-website\index.html"
$Bucket       = "daman-installers"
$DownloadHost = "dl.damansoft.com"

$ApkName = "Daman-Accounting_${Version}_generic.apk"
$ApkPath = Join-Path $BuildDir $ApkName

if (-not (Test-Path $ApkPath)) {
    throw "Not found: $ApkPath - build the Android app first (admin dashboard: Build -> Android (.apk))."
}

Write-Host "==> [1/5] Verifying the APK is signed..." -ForegroundColor Cyan

# apksigner ships with the build-tools, whose directory is versioned. Take the
# highest version present rather than pinning one, so an SDK update does not
# silently break the only check standing between an unsigned build and the
# public download page.
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$apksigner = Get-ChildItem -Path (Join-Path $sdkRoot "build-tools") -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue |
             Sort-Object FullName -Descending |
             Select-Object -First 1

if (-not $apksigner) {
    throw "apksigner not found under $sdkRoot\build-tools. Refusing to publish an APK whose signature cannot be checked - an unsigned one installs on nothing and looks like a broken download."
}

if ($ApkName -match "unsigned") {
    throw "$ApkName is named unsigned. Set damanKeystoreFile / damanKeystorePassword / damanKeyAlias / damanKeyPassword in C:\Users\ASUS\.gradle\gradle.properties and rebuild - see Docs\superpowers\runbooks\android-build.md."
}

# apksigner is a .bat wrapper around a JAR and finds a JVM only through JAVA_HOME or
# `java` on PATH. The deploy agent's PowerShell has neither here, and the wrapper's own
# "JAVA_HOME is not set" arrives as a non-zero exit code - which the check below would
# report as a bad signature, sending the owner off to rebuild an APK that was signed fine.
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = $env:DAMAN_JAVA_HOME
    if (-not $jdk) {
        $jdk = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".jdks") -Directory -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
               Sort-Object Name -Descending |
               Select-Object -First 1 -ExpandProperty FullName
    }
    # Android Studio ships its own JRE, so a machine with the SDK almost always has this one.
    if (-not $jdk -and (Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\java.exe")) {
        $jdk = "C:\Program Files\Android\Android Studio\jbr"
    }
    if (-not $jdk) {
        throw "No JDK found for apksigner. Set JAVA_HOME (or DAMAN_JAVA_HOME) to a JDK and re-run - this says nothing about the APK's signature, only that it cannot be checked."
    }
    $env:JAVA_HOME = $jdk
    Write-Host "    JDK for apksigner: $jdk" -ForegroundColor DarkGray
}
# 2>&1 keeps apksigner's stderr with its output so a real refusal is readable - but in
# Windows PowerShell 5.1 those lines arrive as ErrorRecords, and under the Stop preference
# set at the top of this script the first one throws before $LASTEXITCODE is ever read.
# apksigner warns on stdout and stderr routinely (unprotected META-INF entries on every
# Gradle build, "a restricted method has been called" on JDK 26), so under Stop a passing
# verification aborted the deploy with a NativeCommandError naming no cause. The exit code
# is the verdict; it is captured on the very next line, before anything can overwrite it.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$certs = & $apksigner.FullName verify --print-certs $ApkPath 2>&1
$signerExit = $LASTEXITCODE
$ErrorActionPreference = $previousEap
if ($signerExit -ne 0) {
    Write-Host ($certs | Out-String) -ForegroundColor DarkGray
    throw "apksigner refused $ApkName. An unsigned or badly signed APK installs on no phone - fix the signing config and rebuild before deploying."
}
Write-Host "    Signed OK." -ForegroundColor DarkGray
($certs | Select-String "Signer #1 certificate (DN|SHA-256)") | ForEach-Object {
    Write-Host "    $($_.Line.Trim())" -ForegroundColor DarkGray
}

Write-Host "==> [2/5] Uploading $ApkName to R2..." -ForegroundColor Cyan
npx wrangler r2 object put "$Bucket/$ApkName" --file "$ApkPath" --remote
if ($LASTEXITCODE -ne 0) { throw "Upload failed for $ApkName." }

Write-Host "==> [3/5] Verifying it is live at https://$DownloadHost ..." -ForegroundColor Cyan
$localSize = (Get-Item $ApkPath).Length
$url  = "https://$DownloadHost/$ApkName"
$resp = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 60 -UseBasicParsing
$remoteSize = [int64]$resp.Headers["Content-Length"]
if ($resp.StatusCode -ne 200) { throw "$url returned HTTP $($resp.StatusCode)" }
if ($remoteSize -ne $localSize) { throw "$url size mismatch: local=$localSize remote=$remoteSize - upload may be incomplete." }
Write-Host "    OK: $url ($remoteSize bytes)" -ForegroundColor DarkGray

if ($SkipWebsite) {
    Write-Host ""
    Write-Host "==> Done. $ApkName is live at $url. Website left untouched (-SkipWebsite)." -ForegroundColor Green
    return
}

Write-Host "==> [4/5] Updating daman-website/index.html Android links..." -ForegroundColor Cyan
# Read/write raw UTF-8 without a BOM - index.html has Arabic text, and
# Set-Content's "UTF8" in Windows PowerShell 5.1 always adds one, which shows up
# as a byte-level diff even when nothing else changed.
$html = [System.IO.File]::ReadAllText($WebsiteIndex, [System.Text.Encoding]::UTF8)
# Matches the pre-2026-11-17 name too, so the first release under the new one
# still finds the link that is actually on the page.
$newHtml = $html -replace "https://$DownloadHost/Daman(-Accounting)?_[\d.]+_generic\.apk", $url
# Its own version marker, separate from the Windows .dl-version: the APK is not
# rebuilt for every Windows release and the two numbers drift apart on purpose.
$newHtml = $newHtml -replace '(<span class="dl-apk-version">)[\d.]+(</span>)', "`${1}$Version`${2}"

if ($newHtml -eq $html) {
    Write-Host "    Warning: no links were changed - website may already reference this exact version." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "==> Done. Nothing to commit - website already references APK $Version." -ForegroundColor Green
    return
}

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($WebsiteIndex, $newHtml, $utf8NoBom)
Write-Host "    Updated: $WebsiteIndex" -ForegroundColor DarkGray

Write-Host "==> [5/5] Committing and pushing daman-website..." -ForegroundColor Cyan
$WebsiteDir = Split-Path $WebsiteIndex -Parent
Push-Location $WebsiteDir
try {
    git add -- index.html
    if ($LASTEXITCODE -ne 0) { throw "git add failed in $WebsiteDir." }

    git commit -m "chore: bump Android app to $Version"
    if ($LASTEXITCODE -ne 0) { throw "git commit failed in $WebsiteDir." }

    $commitSha = (git rev-parse --short HEAD).Trim()
    Write-Host "    Committed: $commitSha" -ForegroundColor DarkGray

    git push
    if ($LASTEXITCODE -ne 0) { throw "git push failed - the commit ($commitSha) exists locally in $WebsiteDir but is NOT live yet. Push it manually once you've resolved the issue: cd $WebsiteDir; git push" }

    Write-Host "    Pushed $commitSha - GitHub Pages will redeploy shortly." -ForegroundColor DarkGray
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "==> Done. Android $Version is committed and pushed - live once GitHub Pages redeploys." -ForegroundColor Green

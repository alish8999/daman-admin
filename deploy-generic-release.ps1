<#
.SYNOPSIS
    Deploy a newly-built generic installer to R2 and update the website's download links.

.DESCRIPTION
    Takes a version number, finds the matching 64-bit and 32-bit generic installer .exe files
    in clients-build/generic/, uploads both to the daman-installers R2 bucket (--remote, not the
    local simulated one), verifies both are actually live at dl.damansoft.com with matching file
    sizes, and rewrites daman-website/index.html's four download links (AR/EN x 64-bit/32-bit) to
    point at the new files.

    Also uploads latest.yml and the 64-bit installer's .blockmap, if BuildService.java's
    generic build produced them (only true once package.json's build.publish is a "generic"
    provider pointed at https://dl.damansoft.com/ - see the Windows auto-update feature). These
    are what the shipped app's own update-checker reads to detect and download a newer version;
    without them, everything above still works exactly as before, auto-update just stays inert.
    Missing either file is a warning, not a failure - not every version needs to support auto-
    update retroactively.

    Commits and pushes the index.html change automatically once the links are rewritten -
    daman-website deploys via GitHub Pages on push, so this is what actually makes the new
    version live on the public download page (separate from the two Cloudflare-hosted pieces
    this repo's other deploy scripts handle). The commit is scoped to index.html only, never
    a blanket "git add -A", so nothing else sitting uncommitted in that repo gets swept in.

.PARAMETER Version
    The version string exactly as it appears in the built filenames, e.g. "1.0.4"
    (matches Daman_1.0.4_generic.exe / 32-Daman_1.0.4_generic.exe).

.EXAMPLE
    .\deploy-generic-release.ps1 -Version 1.0.4
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

$BuildDir     = "D:\Daman\src\clients-build\generic"
$WebsiteIndex = "D:\Daman\src\daman-website\index.html"
$Bucket       = "daman-installers"
$DownloadHost = "dl.damansoft.com"

$File64 = "Daman_${Version}_generic.exe"
$File32 = "32-Daman_${Version}_generic.exe"
$Path64 = Join-Path $BuildDir $File64
$Path32 = Join-Path $BuildDir $File32

# Auto-update metadata (Windows generic build only) - optional, unlike the two installers
# above. Present only once package.json's build.publish is a "generic" provider pointed at
# https://dl.damansoft.com/ (see the Windows auto-update feature) and BuildService.java's
# copyUpdateMetadataIfPresent() found them next to the freshly-built 64-bit installer.
$LatestYml     = "latest.yml"
$Blockmap64    = "$File64.blockmap"
$PathLatestYml  = Join-Path $BuildDir $LatestYml
$PathBlockmap64 = Join-Path $BuildDir $Blockmap64

if (-not (Test-Path $Path64)) { throw "Not found: $Path64 - build the generic installer first." }
if (-not (Test-Path $Path32)) { throw "Not found: $Path32 - build the 32-bit generic installer first." }

Write-Host "==> [1/5] Uploading $File64 to R2..." -ForegroundColor Cyan
npx wrangler r2 object put "$Bucket/$File64" --file "$Path64" --remote
if ($LASTEXITCODE -ne 0) { throw "Upload failed for $File64." }

Write-Host "==> [2/5] Uploading $File32 to R2..." -ForegroundColor Cyan
npx wrangler r2 object put "$Bucket/$File32" --file "$Path32" --remote
if ($LASTEXITCODE -ne 0) { throw "Upload failed for $File32." }

Write-Host "==> [3/5] Uploading auto-update metadata to R2 (if present)..." -ForegroundColor Cyan
$uploadedMetadata = @()
if (Test-Path $PathBlockmap64) {
    npx wrangler r2 object put "$Bucket/$Blockmap64" --file "$PathBlockmap64" --remote
    if ($LASTEXITCODE -ne 0) { throw "Upload failed for $Blockmap64." }
    $uploadedMetadata += @{ Name = $Blockmap64; LocalSize = (Get-Item $PathBlockmap64).Length }
} else {
    Write-Host "    Skipping $Blockmap64 - not found (this version wasn't built with auto-update support)." -ForegroundColor Yellow
}
if (Test-Path $PathLatestYml) {
    npx wrangler r2 object put "$Bucket/$LatestYml" --file "$PathLatestYml" --remote
    if ($LASTEXITCODE -ne 0) { throw "Upload failed for $LatestYml." }
    $uploadedMetadata += @{ Name = $LatestYml; LocalSize = (Get-Item $PathLatestYml).Length }
} else {
    Write-Host "    Skipping $LatestYml - not found (this version wasn't built with auto-update support)." -ForegroundColor Yellow
}

Write-Host "==> [4/5] Verifying all uploaded files are live at https://$DownloadHost ..." -ForegroundColor Cyan
$localSize64 = (Get-Item $Path64).Length
$localSize32 = (Get-Item $Path32).Length

$checks = @(
    @{ Name = $File64; LocalSize = $localSize64 },
    @{ Name = $File32; LocalSize = $localSize32 }
) + $uploadedMetadata
foreach ($check in $checks) {
    $url = "https://$DownloadHost/$($check.Name)"
    $resp = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 30 -UseBasicParsing
    $remoteSize = [int64]$resp.Headers["Content-Length"]
    if ($resp.StatusCode -ne 200) { throw "$url returned HTTP $($resp.StatusCode)" }
    if ($remoteSize -ne $check.LocalSize) { throw "$url size mismatch: local=$($check.LocalSize) remote=$remoteSize - upload may be incomplete." }
    Write-Host "    OK: $url ($remoteSize bytes)" -ForegroundColor DarkGray
}

Write-Host "==> [5/5] Updating daman-website/index.html download links..." -ForegroundColor Cyan
# Read/write as raw UTF-8 without adding a BOM - index.html has Arabic text, and
# Set-Content's "UTF8" encoding in Windows PowerShell 5.1 always adds a BOM, which
# would show up as a spurious byte-level diff even when nothing else changed.
$html = [System.IO.File]::ReadAllText($WebsiteIndex, [System.Text.Encoding]::UTF8)
$newHtml = $html -replace "https://$DownloadHost/Daman_[\d.]+_generic\.exe", "https://$DownloadHost/$File64"
$newHtml = $newHtml -replace "https://$DownloadHost/32-Daman_[\d.]+_generic\.exe", "https://$DownloadHost/$File32"

if ($newHtml -eq $html) {
    Write-Host "    Warning: no links were changed - website may already reference this exact version." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "==> Done. Nothing to commit - website already references $Version." -ForegroundColor Green
} else {
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($WebsiteIndex, $newHtml, $utf8NoBom)
    Write-Host "    Updated: $WebsiteIndex" -ForegroundColor DarkGray

    Write-Host "==> Committing and pushing daman-website..." -ForegroundColor Cyan
    $WebsiteDir = Split-Path $WebsiteIndex -Parent
    Push-Location $WebsiteDir
    try {
        git add -- index.html
        if ($LASTEXITCODE -ne 0) { throw "git add failed in $WebsiteDir." }

        git commit -m "chore: bump generic installer to $Version"
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
    Write-Host "==> Done. $Version is now committed and pushed - live once GitHub Pages redeploys." -ForegroundColor Green
}

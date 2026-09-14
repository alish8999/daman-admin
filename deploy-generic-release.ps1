<#
.SYNOPSIS
    Deploy a newly-built generic installer to R2 and update the website's download links.

.DESCRIPTION
    Takes a version number, finds the matching 64-bit and 32-bit generic installer .exe files
    in clients-build/generic/, uploads both to the daman-installers R2 bucket (--remote, not the
    local simulated one), verifies both are actually live at dl.damansoft.com with matching file
    sizes, and rewrites daman-website/index.html's four download links (AR/EN x 64-bit/32-bit) to
    point at the new files.

    Does NOT commit or push automatically - review the diff yourself and push when ready
    (daman-website deploys via GitHub Pages on push, separate from the two Cloudflare-hosted
    pieces this repo's other deploy scripts handle).

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

if (-not (Test-Path $Path64)) { throw "Not found: $Path64 - build the generic installer first." }
if (-not (Test-Path $Path32)) { throw "Not found: $Path32 - build the 32-bit generic installer first." }

Write-Host "==> [1/4] Uploading $File64 to R2..." -ForegroundColor Cyan
npx wrangler r2 object put "$Bucket/$File64" --file "$Path64" --remote
if ($LASTEXITCODE -ne 0) { throw "Upload failed for $File64." }

Write-Host "==> [2/4] Uploading $File32 to R2..." -ForegroundColor Cyan
npx wrangler r2 object put "$Bucket/$File32" --file "$Path32" --remote
if ($LASTEXITCODE -ne 0) { throw "Upload failed for $File32." }

Write-Host "==> [3/4] Verifying both files are live at https://$DownloadHost ..." -ForegroundColor Cyan
$localSize64 = (Get-Item $Path64).Length
$localSize32 = (Get-Item $Path32).Length

$checks = @(
    @{ Name = $File64; LocalSize = $localSize64 },
    @{ Name = $File32; LocalSize = $localSize32 }
)
foreach ($check in $checks) {
    $url = "https://$DownloadHost/$($check.Name)"
    $resp = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 30
    $remoteSize = [int64]$resp.Headers["Content-Length"]
    if ($resp.StatusCode -ne 200) { throw "$url returned HTTP $($resp.StatusCode)" }
    if ($remoteSize -ne $check.LocalSize) { throw "$url size mismatch: local=$($check.LocalSize) remote=$remoteSize - upload may be incomplete." }
    Write-Host "    OK: $url ($remoteSize bytes)" -ForegroundColor DarkGray
}

Write-Host "==> [4/4] Updating daman-website/index.html download links..." -ForegroundColor Cyan
# Read/write as raw UTF-8 without adding a BOM - index.html has Arabic text, and
# Set-Content's "UTF8" encoding in Windows PowerShell 5.1 always adds a BOM, which
# would show up as a spurious byte-level diff even when nothing else changed.
$html = [System.IO.File]::ReadAllText($WebsiteIndex, [System.Text.Encoding]::UTF8)
$newHtml = $html -replace "https://$DownloadHost/Daman_[\d.]+_generic\.exe", "https://$DownloadHost/$File64"
$newHtml = $newHtml -replace "https://$DownloadHost/32-Daman_[\d.]+_generic\.exe", "https://$DownloadHost/$File32"

if ($newHtml -eq $html) {
    Write-Host "    Warning: no links were changed - website may already reference this exact version." -ForegroundColor Yellow
} else {
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($WebsiteIndex, $newHtml, $utf8NoBom)
    Write-Host "    Updated: $WebsiteIndex" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "==> Done. Review the diff, then commit and push to actually go live:" -ForegroundColor Green
Write-Host "    cd D:\Daman\src\daman-website" -ForegroundColor DarkGray
Write-Host "    git add index.html" -ForegroundColor DarkGray
Write-Host "    git commit -m ""chore: bump generic installer to $Version""" -ForegroundColor DarkGray
Write-Host "    git push" -ForegroundColor DarkGray

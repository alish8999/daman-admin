<#
.SYNOPSIS
    Build + deploy a hosted store's daman-frontend to Cloudflare Pages.

.DESCRIPTION
    Builds daman-frontend with the "hosted" configuration (angular.json swaps environment.ts for
    environment.hosted.ts - hosted:true, no API host; the browser derives it from its own hostname
    at runtime), verifies the bundle carries no hardcoded store API host and does not leak
    localhost, verifies the bundled client.config.json is the
    NEUTRAL generic one (a dev-run "Run as this client" can leave a real client's config there -
    that must never be published), then pushes dist\daman-frontend to the given Pages project.

    The packaged Electron installers use the separate "production" configuration and are not
    affected by this script.

.PARAMETER PagesProject
    Cloudflare Pages project name, the one shared project "daman-store". Must already exist
    (npx wrangler pages project create <name> --production-branch=main).
.PARAMETER Branch
    Pages branch to deploy to. Must equal the project's production branch (default "main") -
    without an explicit branch wrangler infers it from git ("master" here) and the deploy would
    land as a PREVIEW that the custom domain never serves.
#>

param(
    [Parameter(Mandatory = $true)][string]$PagesProject,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"

$ProjectDir = "D:\Daman\src\daman-frontend"
$DistDir    = "dist\daman-frontend"     # browser-esbuild builder: no /browser subfolder (unlike the admin app)

Push-Location $ProjectDir
try {
    Write-Host "==> [1/5] Verifying bundled client.config.json is the neutral generic config..." -ForegroundColor Cyan
    $cfgHash = (Get-FileHash "src\assets\client.config.json").Hash
    $genHash = (Get-FileHash "src\assets\client.config.generic.json").Hash
    if ($cfgHash -ne $genHash) {
        throw "src\assets\client.config.json differs from client.config.generic.json (a dev-run left a client's config in the checkout). Run 'Reset dev' in the admin panel, then retry."
    }

    Write-Host "==> [2/5] Building daman-frontend (hosted)..." -ForegroundColor Cyan
    npx ng build --configuration hosted
    if ($LASTEXITCODE -ne 0) { throw "Build failed - see Angular CLI output above." }

    $mainBundle = Get-ChildItem -Path "$DistDir\main-*.js" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $mainBundle) { throw "No main-*.js under $DistDir - build may have failed silently." }

    Write-Host "==> [3/5] Verifying the bundle has no hardcoded store host and no localhost..." -ForegroundColor Cyan
    $bundle = Get-Content $mainBundle.FullName -Raw
    if ($bundle -match [regex]::Escape("viora-api.damansoft.com")) {
        throw "Bundle contains 'viora-api.damansoft.com' - a store-specific API host is hardcoded (environment.hosted.ts regressed). Refusing to deploy."
    }
    if ($bundle -match [regex]::Escape("localhost:8082")) {
        throw "Bundle still contains 'localhost:8082' - would ship a broken frontend. Refusing to deploy."
    }
    Write-Host "    Confirmed: no hardcoded store host, no localhost leaked." -ForegroundColor DarkGray

    Write-Host "==> [4/5] Verifying the installable-app (PWA) artifacts were emitted..." -ForegroundColor Cyan
    # The service worker is enabled on the "hosted" configuration ONLY
    # (angular.json -> build.configurations.hosted.serviceWorker). If that is
    # ever dropped, the build still succeeds and still deploys - it just
    # silently stops being installable, and every phone that already installed
    # it keeps serving its cached copy with no way to be told otherwise. Fail
    # loudly here instead.
    foreach ($required in @("ngsw-worker.js", "ngsw.json", "manifest.webmanifest")) {
        if (-not (Test-Path (Join-Path $DistDir $required))) {
            throw "$required missing from $DistDir - the hosted build did not emit the PWA artifacts. Check that angular.json still sets serviceWorker:true on the 'hosted' configuration. Refusing to deploy."
        }
    }
    $iconCount = (Get-ChildItem (Join-Path $DistDir "assets\icons") -Filter *.png -ErrorAction SilentlyContinue).Count
    if ($iconCount -lt 10) {
        throw "Only $iconCount icon(s) under $DistDir\assets\icons - the home-screen icon set is incomplete. Refusing to deploy."
    }
    Write-Host "    Confirmed: service worker, manifest and $iconCount icons present." -ForegroundColor DarkGray

    Write-Host "==> [5/5] Deploying to Cloudflare Pages (project: $PagesProject, branch: $Branch)..." -ForegroundColor Cyan
    npx wrangler pages deploy $DistDir --project-name=$PagesProject --branch=$Branch --commit-dirty=true
    if ($LASTEXITCODE -ne 0) { throw "wrangler pages deploy failed - see output above." }

    Write-Host ""
    Write-Host "==> Deploy complete." -ForegroundColor Green
}
finally {
    Pop-Location
}

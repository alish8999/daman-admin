<#
.SYNOPSIS
    One-command build + deploy for daman-admin-frontend to Cloudflare Pages.

.DESCRIPTION
    Builds the Angular app in production mode (picks up environment.prod.ts via the
    fileReplacements wired into angular.json's "production" configuration - verifies apiUrl
    actually landed in the built bundle before deploying, so a broken fileReplacements setup
    is caught here instead of shipping a build that silently points at localhost) and pushes it
    to the "daman-admin" Cloudflare Pages project via wrangler.

    wrangler reuses your cached OAuth login from the first "wrangler pages deploy" run, so this
    normally won't prompt for anything - if it does ask you to log in again, that's expected
    (token expired) and just needs a one-time browser approval like before.
#>

$ErrorActionPreference = "Stop"

$ProjectDir  = "D:\Daman\src\daman-admin\daman-admin-frontend"
$DistDir     = "dist\daman-admin-frontend\browser"
$PagesProject = "daman-admin"
$ExpectedApiUrl = "admin-api.damansoft.com"

Push-Location $ProjectDir
try {
    Write-Host "==> [1/3] Building daman-admin-frontend (production)..." -ForegroundColor Cyan
    npx ng build --configuration production
    if ($LASTEXITCODE -ne 0) { throw "Build failed - see Angular CLI output above." }

    $mainBundle = Get-ChildItem -Path "$DistDir\main-*.js" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $mainBundle) { throw "Could not find a built main-*.js bundle under $DistDir - build may have failed silently." }

    Write-Host "==> [2/3] Verifying the build points at the real API, not localhost..." -ForegroundColor Cyan
    $bundleText = Get-Content $mainBundle.FullName -Raw
    if ($bundleText -notmatch [regex]::Escape($ExpectedApiUrl)) {
        throw "Built bundle does not contain '$ExpectedApiUrl' - environment.prod.ts / angular.json fileReplacements may be broken. Refusing to deploy a possibly-broken build."
    }
    if ($bundleText -match "localhost:8083") {
        throw "Built bundle still contains 'localhost:8083' - this would ship a broken frontend. Refusing to deploy."
    }
    Write-Host "    Confirmed: bundle references $ExpectedApiUrl, no localhost leaked in." -ForegroundColor DarkGray

    Write-Host "==> [3/3] Deploying to Cloudflare Pages (project: $PagesProject)..." -ForegroundColor Cyan
    npx wrangler pages deploy $DistDir --project-name=$PagesProject
    if ($LASTEXITCODE -ne 0) { throw "wrangler pages deploy failed - see output above." }

    Write-Host ""
    Write-Host "==> Deploy complete. Check https://admin.damansoft.com to confirm." -ForegroundColor Green
}
finally {
    Pop-Location
}

# Admin Portal — Hosting & Deployment Reference

This documents the live, hosted setup for `daman-admin-backend` and `daman-admin-frontend`,
stood up 2026-09-14. See also `Docs/superpowers/specs/2026-08-29-admin-cloudflare-deploy-design.md`
(in the main `daman-backend` repo checkout) for the original design rationale — this file is the
concrete "what's actually running and how to operate it" reference; that spec is the "why".

## 1. Architecture at a glance

| Piece | Where | Notes |
|---|---|---|
| `daman-admin-backend` | Hetzner CX23 droplet, `162.55.51.189`, `systemd` service | Spring Boot + H2 file DB, behind a Cloudflare Tunnel |
| `daman-admin-frontend` | Cloudflare Pages, project `daman-admin` | Static build, deployed via `wrangler` CLI (not Git integration) |
| Public API domain | `https://admin-api.damansoft.com` | Routes through the Cloudflare Tunnel to the backend's `localhost:8083` |
| Public portal domain | `https://admin.damansoft.com` | Cloudflare Pages custom domain |
| Installer downloads | Cloudflare R2, bucket `daman-installers`, custom domain `https://dl.damansoft.com` | Replaced a Google Drive link on `daman-website` |
| Marketing site | GitHub Pages, `damansoft.com` (see `daman-website`'s own `CNAME` file) | Unrelated hosting method, just noting it deploys via git push, unlike the two above |

No inbound ports are open on the server except SSH — the Tunnel makes an outbound-only connection
to Cloudflare's edge, so the backend is never directly reachable from the internet.

## 2. The server

- Host: Hetzner Cloud, CX23 (4GB RAM / 2 vCPU), Ubuntu, Nuremberg region.
  (Nuremberg capacity was sold out for several plans at signup time — availability is
  per-location, not account-wide; if you ever need a second server, check multiple Hetzner
  locations before assuming a plan is unavailable everywhere.)
- Access: `root@162.55.51.189`, **SSH key auth** (set up 2026-09-14). The key pair is
  `~/.ssh/daman_admin_deploy` (ed25519, no passphrase) with the public half in the server's
  `~/.ssh/authorized_keys`, wired up by a `Host 162.55.51.189` stanza in `~/.ssh/config`
  (`User root`, `IdentityFile ~/.ssh/daman_admin_deploy`, `IdentitiesOnly yes`). Plain
  `scp`/`ssh root@162.55.51.189` from this machine picks that up automatically — **no password
  prompts any more**, which is also what makes a non-interactive deploy (the dashboard in §6.2,
  or a future CI pipeline) possible at all. Password auth is still enabled server-side as a
  fallback; the private key lives only on this dev machine, so treat it like the license key —
  losing the machine means re-adding a new public key via the Hetzner console.
- Firewall: Hetzner Cloud Firewall, inbound SSH only. `fail2ban` installed (password auth is
  still accepted server-side as a fallback, so brute-force protection still earns its keep).
- JDK 21 headless installed via `apt install openjdk-21-jre-headless`.

## 3. Backend (`daman-admin-backend`)

### 3.1 Where things live on the server

- Jar: `/root/daman-admin-backend.jar`
- License key pair: `/root/daman_admin/keys/` (`license-private.pem` / `license-public.pem`)
  — **note the real path is `~/daman_admin/keys/`, not `~/.daman/keys/`** (an earlier draft of
  the deploy spec had this wrong; verified against `LicenseKeyService.java`).
- H2 database: `/root/daman_admin/admin/admin_data.mv.db` (+ `.trace.db`, `.lock.db`)
- `systemd` unit: `/etc/systemd/system/daman-admin-backend.service`
- Log: `/var/log/daman-admin-backend.log`

### 3.2 Why the license key and database matter so much

The backend's `LicenseKeyService` **silently generates a brand-new RSA key pair** on first boot
if it doesn't find one at the path above. If that ever happens on this server instead of using
the real key, every license `.dat` signed afterward would use a different key than the
`license-public.pem` baked into every already-shipped client installer — meaning new licenses
would simply fail to validate on any existing customer's install. There is no way to fix that
after the fact except re-issuing every client's license.

**Before ever restarting a freshly-provisioned instance of this app, confirm the real key and
database are in place.** After any restart, always check the log for:
```
grep "License RSA key pair" /var/log/daman-admin-backend.log | tail -1
```
It must say **"loaded from"**, never "generated and saved to".

### 3.3 Deploying an update

Use the script — don't do this by hand:
```powershell
D:\Daman\src\daman-admin\deploy-admin-backend.ps1
```
It builds the jar (JDK 21 explicitly — IntelliJ's bundled JBR silently breaks Lombok), uploads it,
restarts the `systemd` service, and verifies both the license-key-loaded log line and a `200`
from the public health endpoint (retrying for ~18s since Spring Boot doesn't always finish
starting within a few seconds of a restart). Runs unattended — no password prompts, since both
the `scp` and the `ssh` step authenticate with the SSH key from §2.

A local dashboard for running this script (with live streamed output) is also available — see §6.2.

If you ever do this by hand instead, the three commands are:
```powershell
scp "D:\Daman\src\daman-admin\daman-admin-backend\target\daman-admin-backend-0.0.1-SNAPSHOT.jar" root@162.55.51.189:/root/daman-admin-backend.jar
ssh root@162.55.51.189 "systemctl restart daman-admin-backend"
```

### 3.4 The `scp -r` nested-folder trap (why the initial deploy briefly showed 0 clients)

If an `scp -r <local-folder> user@host:/remote/path` transfer fails partway (e.g. the source file
was locked by a still-running local process — always stop your local admin-backend before copying
its database), the failed attempt can still leave the **destination directory** created on the
remote side. Retrying the same command then copies your local folder **inside** that
already-existing directory instead of filling it — e.g. `/root/daman_admin/admin/admin/admin_data.mv.db`
one level deeper than the app expects, while the app boots against the shallower path, finds
nothing, and silently creates a fresh empty database there.

Symptom: everything looks fine (login works, no errors in the log, CORS fine) except the data is
just... empty. Always verify file sizes match on both ends (`ls -la` locally and remotely) after
any `scp -r`, especially after a first attempt failed.

## 4. Cloudflare Tunnel

- Tunnel name: `daman-admin`, ID `4f0ba07b-dfae-4929-bf81-3cefe14cdaaa`
- Config: `/etc/cloudflared/config.yml` — routes `admin-api.damansoft.com` → `http://localhost:8083`
- Runs as its own `systemd` service (installed via `cloudflared service install`, not the
  hand-written unit pattern used for the Java app)
- Credentials: `/root/.cloudflared/4f0ba07b-dfae-4929-bf81-3cefe14cdaaa.json`

### 4.1 Why the hostname is `admin-api.damansoft.com`, not `api.admin.damansoft.com`

Cloudflare's free Universal SSL certificate only covers **one** wildcard level (`*.damansoft.com`).
`api.admin.damansoft.com` is *two* levels below the apex, which that certificate doesn't cover —
the TLS handshake fails outright (`SEC_E_ILLEGAL_MESSAGE` / fatal TLS alert on Windows' schannel)
even though DNS resolves fine to real Cloudflare IPs. **Any new hostname added under this zone
must stay one level deep** unless Cloudflare's "Total TLS" (SSL/TLS → Edge Certificates) is turned
on for the zone first.

## 5. Frontend (`daman-admin-frontend`)

### 5.1 Deploying an update

```powershell
D:\Daman\src\daman-admin\deploy-admin-frontend.ps1
```
Builds in production mode, **verifies the built bundle actually contains `admin-api.damansoft.com`
and does NOT contain `localhost:8083`** before deploying (refuses to deploy otherwise — see §5.2
for why this check exists), then runs `wrangler pages deploy`. Reuses your cached `wrangler` OAuth
login from the first deploy; only re-prompts if that token has expired.

A local dashboard for running this script (with live streamed output) is also available — see §6.2.

Manual equivalent, if needed:
```powershell
cd D:\Daman\src\daman-admin\daman-admin-frontend
npx ng build --configuration production
npx wrangler pages deploy dist/daman-admin-frontend/browser --project-name=daman-admin
```

### 5.2 `environment.prod.ts` / `fileReplacements` — this didn't exist before this deploy

There was **no `environment.prod.ts`** and **no `fileReplacements` wired into `angular.json`**'s
production build config — meaning every previous "production" build still silently baked in
`http://localhost:8083`. Both are fixed now (`environment.prod.ts` has
`apiUrl: 'https://admin-api.damansoft.com'`), but if this project's environment files are ever
restructured, re-verify a production build actually contains the real API URL before trusting a
deploy — the deploy script's built-in check exists specifically because this bug shipped silently
once already.

### 5.3 Deployment method: direct CLI, not Git integration

Pushing to GitHub has **no effect** on what's live at `admin.damansoft.com` — this project deploys
via the `wrangler pages deploy` CLI command directly from a local build, not Cloudflare Pages' Git
integration. Push to GitHub for source-control hygiene, but a real deploy always needs the script
above run afterward.

(Cloudflare Pages *does* support Git integration as a simpler long-term option — connecting the
repo would make every push auto-deploy, no manual step needed. Not set up yet; worth doing if the
manual step becomes annoying.)

### 5.4 CORS

`daman-admin-backend`'s `CorsConfig.java` allows `https://admin.damansoft.com` in addition to the
local dev ports. If the frontend's domain ever changes, update `allowedOrigins` there too and
redeploy the backend.

## 6. Installer hosting (Cloudflare R2)

- Bucket: `daman-installers`
- Custom domain: `https://dl.damansoft.com` (one level under the apex — fine per §4.1's rule)

### 6.1 Shipping a new generic build

Use the script — it does the whole release end to end:
```powershell
D:\Daman\src\daman-admin\deploy-generic-release.ps1 -Version 1.0.4
```
It finds `Daman_1.0.4_generic.exe` / `32-Daman_1.0.4_generic.exe` in `clients-build/generic/`
(build these first via the admin portal's "Build Generic" button — see §8), uploads both to R2,
verifies each is actually live at `dl.damansoft.com` with a matching file size, rewrites the
four download links in `daman-website/index.html` (AR/EN × 64-bit/32-bit) to point at the new
filenames, and then **commits and pushes that change itself** (scoped to just `index.html`,
never a blanket `git add -A`) — that site deploys via GitHub Pages on push, unlike the two
Cloudflare-hosted pieces above, so this last step is what actually makes the new version live
on the public download page. If nothing changed (the website already referenced this exact
version), it skips the commit instead of creating an empty one.
A local dashboard for running it (with live streamed output) is also available — see §6.2.

Manual equivalent, if needed:
```powershell
npx wrangler r2 object put daman-installers/<filename>.exe --file "<local path>" --remote
```
**The `--remote` flag is not optional.** Without it, `wrangler r2 object put` silently writes to
a local Miniflare-simulated bucket on your own machine — the command reports "Upload complete"
either way, but nothing actually reaches the real bucket. This bit us once already; the script
above guards against it by verifying the live file size afterward, not just trusting the CLI's
own success message. Going this manual route also means updating the four download links in
`daman-website/index.html` yourself, then committing and pushing the change.

- This replaced a Google Drive-hosted download — Drive isn't a great fit for large installers
  (virus-scan warnings past ~100MB, shared-link download quotas, no analytics, tied to a personal
  account).

### 6.2 Deploy Dashboard (optional, local convenience)

Instead of running the three `.ps1` scripts by hand, `deploy-dashboard/` provides a local
browser UI that runs them with live streamed output:

```powershell
cd daman-admin\deploy-dashboard
npm start
```

Then open `http://127.0.0.1:5500/` in a browser. Three cards — Admin Backend, Admin Frontend,
Generic Release — each run the matching script and stream its output live; the Generic Release
version field is pre-filled from the highest version already built in `clients-build/generic/`.

This is a thin wrapper: it spawns the same scripts documented above, unmodified, and adds no
new deploy logic of its own. It depends on the SSH key auth from §2 — a spawned script has no
terminal to type a password into, and its stdin is deliberately closed, so anything that did
prompt would fail fast rather than hang. It binds to `127.0.0.1` only, rejects cross-origin
requests to its `/api/*` endpoints (a deploy is a plain `GET`, so without that check any page
open in the same browser could trigger one), and has no auth of its own beyond that.

## 7. Connecting to the live database locally (IntelliJ / DataGrip)

The server's firewall only allows SSH, so any DB client needs an SSH tunnel — and H2's
`AUTO_SERVER` mode (used for the `admin_data` datasource) has a quirk worth knowing about.

### 7.1 The manual tunnel (works regardless of IDE)

IntelliJ's own built-in "Use SSH tunnel" checkbox has a driver-template bug specific to H2's
`tcp://` URL shape (it can't auto-detect host/port from it, even when a matching "Remote" URL
template already exists in the driver's config — throws "Host & port not found"). Simplest fix:
open the tunnel yourself and leave IntelliJ's own SSH tunnel option **unchecked**.

```powershell
ssh -L 43667:127.0.1.1:43667 root@162.55.51.189 -N
```
(No output, no prompt back — that's correct, it means it's just sitting there forwarding.
Leave the window open for as long as you want the connection to work.)

The port (`43667` above) is **not fixed** — H2's `AUTO_SERVER` mode picks a port dynamically each
time the app starts. Before connecting, always check the current port (and see §7.2 for the ID
you'll also need):
```bash
cat /root/daman_admin/admin/admin_data.lock.db
```
Look for `server=127.0.1.1:<port>`.

### 7.2 The critical part: the JDBC URL needs the lock file's `id`, not the file path

This is the one that cost the most time to figure out. `AUTO_SERVER` mode doesn't let a TCP client
connect using the actual file path (`root/daman_admin/admin/admin_data`) — it requires the
randomly-generated `id` from the same lock file, used as a one-time security key
(`org.h2.server.TcpServer.checkKeyAndGetDatabaseName` — confirmed by reading H2 2.4.240's own
source). Using the file path instead throws a **misleading** `Wrong user name or password`
error that has nothing to do with the actual `sa`/blank credentials being wrong.

```bash
cat /root/daman_admin/admin/admin_data.lock.db
```
```
#FileLock
hostName=ubuntu-4gb-nbg1-1
id=1a09f38f17262ebecd53dfd2056374b11acb41c2675      <-- use THIS as the "database" part of the URL
method=file
server=127.0.1.1:43667                              <-- use THIS port
```

Correct connection:
- **URL**: `jdbc:h2:tcp://localhost:<port from lock file>/<id from lock file>`
- **User**: `sa`, **Password**: (blank)

**This `id` (and the port) change every time the backend restarts.** There's no way to make this
a stable, one-time connection string — re-check the lock file after any redeploy or restart before
reconnecting.

### 7.3 Why not just switch this database to SQLite to avoid all this?

Considered and deliberately rejected. This is live production data (client records, signed
licenses, billing history) — migrating engines means an actual data migration, not a driver swap,
and this codebase has a well-documented history of SQLite-specific dialect bugs (stale CHECK
constraints on enum columns, Hibernate `getFloat()` precision truncation, etc. — see the main
`daman-backend` repo's `CLAUDE.md`) that admin-backend's own queries have never been tested
against. All of that risk to solve a local DB-browsing convenience problem, for a tool that
already works. Not worth it.

## 8. Building the generic installer itself

The "Build Generic" button in the admin portal (`BuildService`) still only works against your
**local** admin-backend/admin-frontend (`localhost:8083` / `:4201`), not the hosted
`admin.damansoft.com` instance — and that's fine, not a gap to fix. Two reasons:

1. It reads static, checked-in config (`client.config.generic.json`), never anything from the
   admin database — so it was never tied to *which* admin-backend instance you're using.
2. It needs the full local toolchain (Maven, JDK, Node, `electron-builder`) plus a checkout of
   `daman-backend`/`daman-frontend` — none of which has any reason to live on the tiny hosting
   box. The hosted instance is for remote client/license/billing management; building installers
   stays a local-machine operation.

Output lands in `D:\Daman\src\clients-build\generic\` as `Daman_<version>_generic.exe` /
`32-Daman_<version>_generic.exe` — feed that version into `deploy-generic-release.ps1` (§6.1) to
actually ship it.

The longer-term plan (not started — see §9) is a GitHub Actions release-tag workflow that builds
in CI instead, fully decoupling this from any admin-backend instance or local machine.

## 9. What's NOT done yet (tracked follow-ups)

- **Nightly H2 → R2 backup cron.** The server is currently a single point of failure — no
  redundancy at all if the disk fails or the instance is lost.
- **Offline backup of the license private key** (`/root/daman_admin/keys/license-private.pem`).
  Losing this means no new license can ever be signed again for the existing public key. Should
  live in a password manager or similar, not just on the one server.
- **GitHub Actions CI/CD.** Both deploy scripts are still manually triggered from a local machine
  (or from the dashboard in §6.2, which is still local). Cloudflare Pages' Git integration would be
  the easy win for the frontend specifically (§5.3); the backend now has what it was missing — SSH
  key auth is set up (§2), so a CI workflow would only need that private key as a repo secret.
- **Release-tag → R2 pipeline + public `/get/<token>` download page** — the longer-term pieces
  from the original deploy spec's §6–7, not started.

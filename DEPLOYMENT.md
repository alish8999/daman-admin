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

**Also uploads `latest.yml` + the 64-bit installer's `.blockmap`, and `latest-x86.yml` + the
32-bit installer's `.blockmap`, if present** (added 2026-09-26/27 for the Windows generic-build
auto-update feature — `daman-frontend`'s `package.json` `build.publish` is a `generic` provider
pointed at `https://dl.damansoft.com/`, and `BuildService.java`'s
`copyUpdateMetadataIfPresent()` copies these out of `dist-electron/` into `clients-build/generic/`
alongside the installer, before that directory gets wiped). These files are what the shipped
app's own update-checker reads to detect and download a newer version.

**Two separate channel files, one per architecture — this is load-bearing, not cosmetic.**
electron-updater has no built-in per-architecture channel file on Windows (confirmed in its own
source: *"due to historical reasons for windows we use channel name without platform
specifier"*) — every Windows install, 32-bit or 64-bit, would otherwise request the exact same
`latest.yml`. A real bug hit during live testing: the 32-bit build (which uses a separate
`electron-builder.win-x86.json` config) was silently overwriting the correct 64-bit `latest.yml`
with one referencing a filename that was never uploaded, breaking auto-update for *every*
architecture on every release, regardless of build order. Fixed by giving 32-bit installs their
own channel (`update-checker.js` sets `autoUpdater.channel = 'latest-x86'` when
`process.arch === 'ia32'`), which is why `electron-builder.win-x86.json` needs its own
`nsis.artifactName` and why this script uploads a distinct `latest-x86.yml` alongside the
64-bit `latest.yml` rather than sharing one file. Each uploads to the bucket root, unversioned
— every release overwrites its own architecture's previous file, never the other one's.

Missing any of these four metadata files only logs a warning and skips it — every older
already-built version, and anything built before this feature existed, has none of them, and
that's fine: those installs just don't get an update-check notification, nothing breaks.

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

Instead of running the `.ps1` scripts by hand, `deploy-dashboard/` provides a local
browser UI that runs them with live streamed output:

```powershell
cd daman-admin\deploy-dashboard
npm start
```

Then open `http://127.0.0.1:5500/` in a browser. Each card runs the matching script and streams
its output live: Admin Backend, Admin Frontend, Generic Release (its version field is pre-filled
from the highest version already built in `clients-build/generic/`), and a **Hosted Store** group:

- **Create online client** - provisions one hosted store (see §10.2).
- **Hosted Store Backend (all stores)** - `deploy-store-backend.ps1`: builds `daman-backend`, ships
  the one shared `/root/daman-store.jar`, and restarts every store one at a time. It takes **no
  arguments** (no client code, no port: the provisioning endpoint owns the per-store units) and
  needs `DAMAN_ADMIN_TOKEN` set in the dashboard's environment (see §10.3).
- **Hosted Store Frontend (shared)** - `deploy-store-frontend.ps1`: deploys the one shared
  `daman-store` Pages project.

The only hosted-store argument is the Pages project name, fixed in `deploy-dashboard/lib/slots.js`
and never taken from the request. The dashboard has to be restarted (`Ctrl+C`, `npm start`) to
pick up slot changes.

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

## 10. Hosted ("online") stores - runbook

Hosted stores are the online edition of the POS, sold alongside the desktop installer. Design
rationale: `Docs/superpowers/specs/2026-10-24-online-store-provisioning-design.md`. This section is
what to run and what to do when it breaks. `daman-backend` was **not** changed for this feature:
everything it needs per store is an existing property or JVM flag, so the POS-backend reference docs
(`Docs/ARCHITECTURE.md` etc.) are unaffected. The endpoints below belong to the *admin* backend.

### 10.1 How it fits together

- **One shared frontend.** A single Cloudflare Pages project, `daman-store`, serves every hosted
  customer. It is deployed once (Hosted Store Frontend card), not per customer. The frontend works
  out its API host at runtime, so one build serves all of them.
- **One shared jar.** `/root/daman-store.jar` runs every store.
- **Per store:** its own systemd unit `daman-store-<clientCode>`, its own port (from 8091 upward),
  and `-Duser.home=/root/stores/<clientCode>`. That flag is what isolates the store's `license.dat`,
  `machine-id.cache`, `clock.dat`, `active-client.json`, SQLite database and log. **Never remove
  it:** without it a second store's licence activation overwrites the first store's licence file,
  and the first store boots on the wrong licence after its next restart, with no error.
- **Per-store JWT secret** (`DAMAN_JWT_SECRET` in the unit). The backend's default secret ships
  inside every installer, which is a public download, and its JWT filter trusts role/permissions
  straight from the token claims with no database lookup. Without a per-store secret, anyone with
  the installer could mint an admin token accepted by every hosted store. The secret is kept in the
  registry row but the API deliberately cannot return it. It is inlined in the store's own unit,
  but that is deliberate and safe: the provisioner writes each unit `rw-------` (root-only;
  systemd reads units as root, so this costs nothing), unlike the systemd default of world-readable.
- **Backups:** the app's own existing scheduler, pointed at `/root/stores/<clientCode>/backups`
  (`daman.backup.dir`). No separate backup code. It writes one `daman_db_YYYY-MM-DD.zip` per day
  (it *checks* every `daman.backup.check-interval-hours`, default 6, but a day's file is only made
  once) and prunes beyond `daman.backup.retention-days` (default 14), so a store keeps about two
  weeks of history.
- **Registry:** each store is a `HostedInstance` row in the admin database (`GET /api/hosted-stores`):
  client code, subdomain, port, service name, hostnames, `status` (`PROVISIONING` | `ACTIVE` |
  `FAILED`) and `failedStage`.
- **viora is a deliberate exception:** its own jar (`/root/daman-store-viora.jar`), its own Pages
  project, and its legacy data directory `/root/.daman/viora`. It is excluded from the shared-jar
  release by service name (`daman-store-viora`). Its registry row exists only so port 8090 is never
  reallocated to someone else.

### 10.2 Creating an online client

In the deploy dashboard, the **Create online client** card:

1. Paste the remote admin token (the Bearer token from an admin-portal login) to load the client list.
2. Pick the client (it must already exist in the admin panel).
3. Confirm or edit the suggested subdomain.
4. Set the licence expiry. **Blank = a perpetual licence.**
5. Click **Create**.

Notes:
- **The card answers immediately and then watches.** The POST comes back in under a second with a
  `PROVISIONING` row; the card says `Provisioning... (stage X)` and polls the registry every 5 s
  until that row reads `ACTIVE` or `FAILED`. Expect **about two minutes** of that — the Pages custom
  domain alone takes ~105 s to go from `pending` to `active`. The Create button stays disabled
  while it polls.
  - Why it is not one blocking request: the whole sequence takes 125-150 s, and
    `admin-api.damansoft.com` is reached through the Cloudflare tunnel, so the request crosses
    Cloudflare's edge — which **returns 524 after 100 s of origin silence**. Blocking reported a red
    "HTTP 524" card for every provision that in fact succeeded, and a second click then answered
    409, which looked like another failure.
  - If the card says it **stopped polling after 6 minutes**, the server has *not* stopped. Read the
    row (§10.5) before doing anything else.
  - Clicking Create again while a provision for that client is still running answers **409** and
    starts nothing — one run per client at a time, deliberately (two concurrent runs would mint two
    `ACTIVE` licence rows for the same store and wedge every later licence operation on it).
- The subdomain suggestion is legitimately **empty** when the client code has no usable ASCII letter
  or digit (e.g. an Arabic-only shop name). Nothing invents a hostname: type one (lowercase ASCII
  letters, digits, hyphens; one level deep only, see §4.1).
- Under the hood this is `POST /api/hosted-stores` with `{clientCode, subdomain, expiresAt}`,
  admin-token gated:
  - **202** with the `PROVISIONING` row — accepted; watch `GET /api/hosted-stores` for the outcome.
    A mid-sequence failure shows up there as a `FAILED` row with its `failedStage`, never as an
    error from this POST.
  - **400** — a refusal: the input is wrong and nothing was persisted.
  - **503** — this server cannot provision right now: no RAM headroom (§7 of the spec: the box holds
    about three stores) or `CLOUDFLARE_*` not configured. The request was fine; fix the server and
    resubmit unchanged.
  - **409** — the client already has an `ACTIVE` store (body is that row), or a provision for it is
    already running (body is an `error`).
- Stages, in order: `ALLOCATE`, `DATA_DIR`, `UNIT`, `START`, `INGRESS`, `API_DNS`, `FRONTEND_DNS`,
  `PAGES_ATTACH`, `PAGES_ACTIVE` (the ~105 s wait), `LICENCE`, `VERIFY`.
- `VERIFY` ends by fetching **`https://<sub>-api.damansoft.com/api/health`** — the store's own public
  URL, through Cloudflare, the way a customer reaches it. Everything before it probes `127.0.0.1`, so
  this is what makes `ACTIVE` mean "reachable" rather than "the JVM is up". A `FAILED` row at
  `VERIFY` whose message mentions the public URL means the store is running but its ingress rule or
  its DNS record is wrong — see §10.5.

### 10.3 Credentials the admin backend needs

- **Cloudflare API token** (`CLOUDFLARE_API_TOKEN`). Needs **Pages:Edit** on the account **and
  DNS:Edit** on `damansoft.com`. `cloudflared`'s `cert.pem` is not a substitute: it can create
  tunnel CNAMEs but not Pages ones. The backend also reads `CLOUDFLARE_ACCOUNT_ID`,
  `CLOUDFLARE_ZONE_ID` and `CLOUDFLARE_TUNNEL_ID` (see `application.yml`).
- **Where it lives:** *intended* home in production is a root-only `EnvironmentFile=` referenced by
  the admin backend's systemd unit. **This is not confirmed as set up:** §3.1 documents that unit
  and mentions no such file, so check the current unit first, and add it to §3.1 once it exists. The
  token must never be in a git-tracked file, nor inline in the admin unit file (units are
  world-readable by default). The dev-machine copy is `C:\Users\ASUS\.cloudflare\daman-provisioning.token`.
- **Rotate it** once provisioning is in real use: it passed through a chat transcript during design.
- **`DAMAN_ADMIN_TOKEN`** must be set in the *dashboard's* environment (before `npm start`) for the
  all-stores release, or `deploy-store-backend.ps1` stops immediately: the restart route is
  token-gated and the script has no other way to get a token.

### 10.4 Releasing a new backend to every store

Run the **Hosted Store Backend (all stores)** card (or `deploy-store-backend.ps1`). It refuses to
ship anything but a generic build, checks the token against the server before changing anything,
builds, uploads `daman-store.jar.new`, copies the old jar to `/root/daman-store.jar.prev`, renames
the new one into place, then calls `POST /api/hosted-stores/restart-all` on the server.

The walk restarts `ACTIVE` stores one at a time in **id order** (the oldest paying customer is the
canary), polling `/api/health` after each (up to ~2 minutes per store), and **halts at the first
store that does not come back healthy**.

The route answers `{walked, skipped}`, and the script prints both: each restarted store, then each
row it passed over with the reason (`not ACTIVE (FAILED at stage ...)`, or viora running its own
jar). **Read the skipped list.** If `walked` is empty the script prints a loud warning instead of
"Release complete": the new jar is already in place but nothing was restarted, so every store is
still running the old jar from memory and will pick the new one up on its next unsupervised
restart. A store stuck at `FAILED` while its unit runs perfectly well looks exactly like this — fix
its row (§10.5), then re-run the release.

Read the guarantee honestly:

- It is **delayed and sequenced exposure, not containment.** The new jar is already on disk when the
  walk starts and every unit has `Restart=always`, so a store the walk never reached picks the new
  jar up by itself on its next restart.
- The health check only catches "does not boot". A jar that boots but misbehaves passes, and the
  walk carries on to every store.
- **There is no auto-rollback.** On a halt the script prints a one-line manual rollback. In essence:
  Run from the Windows dev machine; every line carries its own `ssh`, so each is safe to paste alone:
  ```bash
  ssh root@162.55.51.189 'mv -f /root/daman-store.jar.prev /root/daman-store.jar'
  ssh root@162.55.51.189 'systemctl restart daman-store-<clientCode>'      # the halted store
  ssh root@162.55.51.189 'journalctl -u daman-store-<clientCode> -n 100'   # why it failed
  ```
  Stores after the halted one were not restarted but will load the new jar on any restart, so roll
  back before then if the jar is really bad.
- **Trap:** re-running the deploy after a halt overwrites `.prev` with the already-bad jar, so the
  rollback above only works until the next deploy. Roll back (or copy `.prev` aside) *before*
  deploying again.
- viora is not touched by this release.

### 10.5 A provision failed: reading the row and resuming

Provisioning writes the stage it is *about to* attempt into the registry row before running it, so
a `FAILED` row's `failedStage` is exactly where it stopped. Nothing is torn down automatically.

1. Look at the row: `GET /api/hosted-stores` (admin Bearer token). Find the client, read `status`
   and `failedStage`.
2. Fix the cause. Typical: `CLOUDFLARE_*` missing or the token lacking a permission (`API_DNS`,
   `FRONTEND_DNS`, `PAGES_*`); a service that will not start (`START`:
   `journalctl -u daman-store-<clientCode>`); a slow Pages domain (`PAGES_ACTIVE`: just re-run); the
   store running but unreachable at its own public URL (`VERIFY` — cloudflared did not pick up the
   new ingress rule, or the `<sub>-api` DNS record points somewhere else; the row's failure message
   names the checks). A re-run that hits a *refusal* (unknown client, bad subdomain) returns **400**,
   and one that hits **503** (no capacity, Cloudflare not configured) leaves the `FAILED` row exactly
   as it was: if `failedStage` has not moved and you got a 400 or a 503, nothing advanced.
3. **Re-run Create for the same client** (same card, or `POST /api/hosted-stores`). It resumes from
   `failedStage` rather than starting over, and answers **202** again.
   - A `PROVISIONING` row is *also* resumable the same way — that is what a run interrupted by an
     admin-backend restart leaves behind. The only thing that refuses a re-run is a provision for
     that client still running **right now** (409).
4. Re-running for a client that is already `ACTIVE` does nothing: it answers **409** with the
   existing row.

A 400 with an `error` message is a refusal before anything was created (e.g. missing subdomain):
nothing to clean up, fix the input and resubmit.

#### Abandoning a half-created store (a mistyped client, for instance)

`DELETE /api/hosted-stores/<clientCode>` (admin Bearer token) **forgets the registry row**:

```bash
ssh root@162.55.51.189 "curl -s -X DELETE -H 'Authorization: Bearer <admin-token>' \
  http://localhost:8083/api/hosted-stores/<clientCode>"
```

- It **refuses an `ACTIVE` row** (409). Spec §5.4's "no suspend, stop or delete" decision is about
  live stores and still stands; this route exists only for a store that never finished. It also
  refuses while that client's provision is actually running (409).
- Why it exists: pick `acem` instead of `acme`, let it half-complete, and there was previously no
  way out — the resume refuses to rename a row, `existsBySubdomain` then blocks reusing `acme` on
  the correctly-named client, and the H2 console is disabled, so the only escape was an H2 shell
  over SSH.
- **It is registry-only. Nothing is torn down** (the same stance as a failed provision). Still
  present after the delete, and yours to remove by hand if the store is really being abandoned:
  - the systemd unit `/etc/systemd/system/daman-store-<clientCode>.service` — and it has
    `Restart=always`, so **stop and disable it** or it keeps running and holding its port:
    `systemctl disable --now daman-store-<clientCode>` then `rm` the unit and `systemctl daemon-reload`;
  - the data directory `/root/stores/<clientCode>` (its SQLite database and backups);
  - the cloudflared ingress rule for `<sub>-api.damansoft.com` in `/etc/cloudflared/config.yml`
    (edit by hand, keep every other rule and the trailing `http_status:404` catch-all, then
    `systemctl restart cloudflared` - never SIGHUP, see 10.6);
  - both DNS records (`<sub>` and `<sub>-api`) in Cloudflare;
  - the `<sub>.damansoft.com` custom domain on the `daman-store` Pages project;
  - the minted licence row in the admin database (harmless: it is bound to a client code nothing
    serves any more).
- What the delete *does* free immediately: that client code, its subdomain and its port, so the
  correctly-named client can be provisioned. Note the port is only free in the **registry** — if the
  old unit is still running, the allocator's listening-port cross-check steps over it anyway.

### 10.6 Never SIGHUP cloudflared (incident, 2026-10-25)

Provisioning edits the shared `/etc/cloudflared/config.yml` and then has to make `cloudflared`
pick up the new rule. It used to do that with `systemctl kill -s HUP cloudflared`. **That does not
reload cloudflared — it stops it.** The journal from the first live provision:

```
cloudflared.service: Sent signal SIGHUP to main process 172707 (cloudflared) on client request.
cloudflared.service: Deactivated successfully.
```

Because that exit is *clean*, `Restart=on-failure` treated the unit as finished and never revived
it. The admin API and viora — a live paying store — were unreachable for about 4½ minutes
(Cloudflare `530`, `error code: 1033`) until the tunnel was started by hand. The same trap as
`daman-store-viora.service`, which was moved off `on-failure` for the same reason: a clean exit
systemd reads as success.

What to know:

- **Provisioning now runs `systemctl restart cloudflared`** and then polls `systemctl is-active`
  for ~30 s, failing the `INGRESS` stage loudly if the tunnel does not come back. A failed
  provision is resumable; a silently dead tunnel is not.
- **The restart briefly interrupts every hostname on the tunnel**, viora included, for a few
  seconds on each provision. That is a deliberate trade against a multi-minute outage. Provision
  outside trading hours if a few seconds matters.
- The earlier objection to restarting — that it would drop the tunnel carrying the provisioning
  request itself — no longer applies: Create answers `202` immediately and the stages run in the
  background, so nothing is in flight through the tunnel.
- **`cloudflared.service` should carry `Restart=always`**, so a mis-signal or a crash self-heals:

  ```
  ssh root@162.55.51.189 'mkdir -p /etc/systemd/system/cloudflared.service.d && \
    printf "[Service]\nRestart=always\nRestartSec=5\n" \
      > /etc/systemd/system/cloudflared.service.d/10-restart-always.conf && \
    systemctl daemon-reload && systemctl show -p Restart --value cloudflared'
  ```

  `daemon-reload` re-reads the unit without restarting the service, so this is safe to apply while
  stores are serving. **Not yet applied.**

If the tunnel is ever down: `systemctl start cloudflared`, confirm with `systemctl is-active
cloudflared` and `journalctl -u cloudflared -n 20` (look for `Registered tunnel connection`), then
re-run Create to resume the provision from `INGRESS`.

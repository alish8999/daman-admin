# Creating a demo slot — runbook

Current state: **manual**. Four steps, two with traps that have already caused a crash-looping
store once. The flavour picker and the Reset button described in the design are not built yet; when
they are, this file collapses to "press Reset".

Everything below is done over SSH against `root@162.55.51.189`, with an **admin session token**
from an admin-portal login. Those tokens expire in hours, so get a fresh one each time — a stale
one fails with `401` on the first call.

---

## 0. Before you start

- **RAM is the limit, not disk.** Each store runs ~800 MB and the box has 3.8 GB with **no swap**,
  so overcommitting kills processes rather than slowing them. Check headroom first:
  `free -m` and `systemctl list-units 'daman-store-*'`.
- Keep demo slots **suspended** when not in use. Suspend is `stopAndDisable`, so a suspended slot
  costs zero RAM, and Resume brings it back.

## 1. Create the client

A hosted store needs a client record first — provisioning mints its licence from that config.
Easiest is to copy an existing client and change what matters:

```bash
curl -s -H "Authorization: Bearer $T" http://localhost:8083/api/clients/viora > /tmp/t.json
# edit: clientCode, appName, tagline, storeType, baseCurrency, pointOfContact, clientNotes
# features: all true EXCEPT the demo defaults below
curl -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     --data @/tmp/new.json http://localhost:8083/api/clients      # expect 201
```

**Demo feature defaults.** Everything on, so a prospect sees the whole product, **except**:

| Flag | Why off |
|---|---|
| `tableOrders` | owner's instruction, 2026-10-30 — not wanted in a demo by default |
| `consignment` | same |

Admin credentials come from the client config: `admin` / `123456`.

**Base currency must match the seed you are going to load** — `SYP` for the market demo, `USD` for
the mobile one. A mismatch between the config and the database's own lock is the documented way to
brick or silently corrupt an installation.

## 2. Provision the hosted store

```bash
curl -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     -d '{"clientCode":"demoN","subdomain":"demoN"}' http://localhost:8083/api/hosted-stores
```

Answers **202** immediately and runs its stages on a background thread — it does not block, because
holding the response past 100 s trips Cloudflare's origin timeout and reports a failure for a
provision that in fact succeeded. Takes about **3 minutes**. Poll until `status: ACTIVE`:

```bash
curl -s -H "Authorization: Bearer $T" http://localhost:8083/api/hosted-stores
```

This creates DNS records, a tunnel ingress rule and a Pages binding — it is **outward facing**, and
the subdomain is public from that moment.

## 3. Load the demo data

> **Trap 1 — the WAL.** Delete `-wal` and `-shm` alongside the database. Leave them and SQLite
> replays a stale journal over the file you just copied in.

```bash
D=/root/stores/demoN/.daman/demoN
systemctl stop daman-store-demoN
sleep 2
rm -f "$D/daman_db.sqlite-wal" "$D/daman_db.sqlite-shm"
cp /root/demo-seeds/mart.sqlite "$D/daman_db.sqlite"     # roll dates FIRST — see step 4
systemctl start daman-store-demoN
```

The store applies any outstanding Flyway migrations on boot (the seed was built at V47; the shipped
jar is at V49) and `DatabaseSeeder` recreates `master` and the client admin, because the seed ships
with an empty `users` table on purpose.

Give it **60–75 seconds**: 2 vCPU and a 4.4 MB database make for a slow start.

## 4. Roll the dates — before copying, not after

> **Trap 2 — date shapes.** This database stores two formats: `yyyy-MM-dd HH:mm:ss` and bare
> `yyyy-MM-dd` (7,078 values, on `acc_journal_entries.entry_date` and three adjustment tables).
> Writing 19 characters everywhere turns a `LocalDate` column into `'2026-10-07 00:00:00'`, the
> entity's AttributeConverter throws `DateTimeParseException: unparsed text found at index 10` on
> the first read, and the application context dies at boot — a crash loop, not a warning.

`RollDates` preserves both shapes and **exits non-zero if any column's shape changes**, so run it
and check it says `stored date shapes preserved on all 71 columns`. See `README.md` for the command.

Without this the demo opens on a dashboard with no sales today — a shop that looks dead.

## 5. Verify

```bash
systemctl is-active daman-store-demoN
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:<port>/api/health        # 200
curl -s http://localhost:<port>/api/license/client-config                          # check flags
grep -ci ERROR $D/backend.log                                                      # expect 0
```

Then the public path, **from the server** — a local machine without IPv6 cannot reach Cloudflare
here and will report `000` for a perfectly healthy store:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://demoN.damansoft.com/
```

---

## Changing features after the fact

**Editing the client config is not enough.** The store reads its features from the signed licence
on disk, so the config change has no effect until a new licence is minted and pushed:

```bash
curl -X PUT  ... http://localhost:8083/api/clients/demoN            # update the flags
curl -X POST ... http://localhost:8083/api/licenses/client/demoN/revoke
curl -X POST ... http://localhost:8083/api/licenses/generate \
     -d '{"machineId":"<from /root/stores/demoN/.daman/machine-id.cache>","clientCode":"demoN"}'
curl -X POST -H 'Content-Type: application/json' \
     -d '{"licenseKey":"<minted key>"}' http://localhost:<port>/api/license/activate
systemctl restart daman-store-demoN
```

`generate` answers **409** if an ACTIVE licence already exists for that machine and client, which is
why the revoke comes first.

## Handing it out, and taking it back

- Resume the slot, send `https://demoN.damansoft.com` with `admin` / `123456`.
- When they are done: suspend it, then repeat steps 3–4 to reset it for the next person.

## Known slots

| Slot | Flavour | Subdomain | Port | Created |
|---|---|---|---|---|
| `demo1` | mart (SYP) | demo1.damansoft.com | 8093 | 2026-10-30 |

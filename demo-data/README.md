# Demo data pipeline

Builds the databases handed to prospects in a demo slot. Two flavours:

| Flavour | Source | Currency | Shape |
|---|---|---|---|
| `mobile` | `DemoDataSeeder` in `daman-backend` | USD | Phone & electronics shop, 10 products, Jan–Apr 2026 |
| `mart`   | derived from a real client backup by the tools here | SYP | Supermarket, 50 products, ~4,700 sales over 23 days |

Between them a prospect sees both currency modes without extra work.

---

## Why `mart` is derived and not copied

The source is one Syrian mart's **live** database. It contains 51 real customers and 73 real
suppliers with names, phone numbers and addresses, three real logins, the shop's own name, address,
phone and tax number, its receipt logo, and its true turnover.

Handing that to a prospect — who may be a competitor two streets away — would leak a paying
client's confidential data. So the catalogue and the *shape* of the trading are kept, and
everything identifying is replaced.

**The source backup is deliberately not in this repo.** It stays wherever the owner keeps it; these
tools take its path as an argument.

### What is kept

- The catalogue: product names, units, packagings, shelf prices. Grocery product names and prices
  are not confidential, and they are what makes a demo look like a real shop instead of three test
  products called "aaa".
- The trading rhythm: every one of the ~4,679 sales, their line amounts, dates and daily peaks.

### What is replaced or removed

- Customers and suppliers → invented Syrian names, generated phone numbers, no addresses, no notes.
- Shop identity (`installation_setup_settings`) → "Daman Demo Market".
- Receipt logo → cleared. A demo that prints a real shop's logo is worse than one with no branding.
- Backup directory path → cleared.
- All users and `user_permissions` → **emptied, not rewritten**, so `DatabaseSeeder` recreates
  `master` and the client admin from the installation's own config on first boot. No real password
  hash ever ships.
- Audit log → cleared.
- Free-text notes on payments, expenses, sales, purchases, shifts, balance adjustments and 3,372
  stock adjustments → blanked. Nobody can promise a hand-typed note contains no name, and a demo
  needs none of them.

---

## Why line amounts survive a product remap

Cutting 1,553 products down to 50 orphans 69% of the line items, and only 52% of sales keep even
one line — the demo would lose half its sales and stop looking like a busy shop.

So orphaned lines are **re-pointed** at a kept product (same category where possible) with their
quantity and prices untouched. Sale totals therefore never move. That matters beyond tidiness:
7,009 journal entries were posted against those totals, and shifting them would leave the
Accounting page disagreeing with the Reports page — in a demo whose whole job is showing those
screens.

The visible trade-off: the product mix is more concentrated than a real mart's, and a line's unit
price may not match its product's current shelf price. Neither is noticeable while demoing.

---

## Why dates are rolled on every reset, not once

A demo whose data stops months ago opens on a dashboard with no sales today, nothing this week and
nothing this month — a shop that looks dead, which is the opposite of the point. The `mobile`
seeder has exactly this problem: its data ends in April 2026.

`RollDates` shifts every timestamp column by **the same whole number of days**, so a payment still
falls after its sale, a purchase still precedes the stock it created, and each sale keeps its
original time of day. It finds date columns by scanning every table rather than from a hand-written
list, which would quietly miss a column the next time a feature adds a table.

Run it as part of **every** reset, not at build time.

---

## Usage

Needs JDK 21 and the SQLite JDBC driver from the local Maven repo.

```powershell
$jdk = "C:\Users\ASUS\.jdks\ms-21.0.10\bin\java.exe"
$jar = "C:\Users\ASUS\.m2\repository\org\xerial\sqlite-jdbc\3.47.0.0\sqlite-jdbc-3.47.0.0.jar"

# 1. Derive the demo from a real backup (50 products by default)
& $jdk "-Dfile.encoding=UTF-8" -cp $jar demo-data/tools/BuildMartDemo.java `
      <path-to-real-backup>.sqlite  out/mart-demo.sqlite  50

# 2. Prove nothing identifying survived — prints counts only, never values
& $jdk "-Dfile.encoding=UTF-8" -cp $jar demo-data/tools/LeakCheck.java out/mart-demo.sqlite

# 3. Roll the dates so the newest sale is today
& $jdk "-Dfile.encoding=UTF-8" -cp $jar demo-data/tools/RollDates.java out/mart-demo.sqlite

# 4. Gate: refuses to pass if identity survived or referential integrity broke
& $jdk "-Dfile.encoding=UTF-8" -cp $jar demo-data/tools/VerifyDemo.java out/mart-demo.sqlite

# 5. Optional: confirm it will look alive when opened
& $jdk "-Dfile.encoding=UTF-8" -cp $jar demo-data/tools/Liveness.java out/mart-demo.sqlite
```

`VerifyDemo` exits non-zero on a problem, so it can gate a build.

### A note on verifying Arabic

`VerifyDemo` asserts the anonymised names by **comparing against the expected values**, not by
printing them. A Windows console renders Arabic as `????`, which proves nothing either way — the
comparison proves both that the scrub ran and that UTF-8 survived the round trip.

### A note on the integrity check

An early version flagged 80 sales whose totals did not match their lines. Running the identical
check against the untouched source returned **exactly 80**, which showed the derivation was
innocent and the check simply did not model discounts, rounding and returns. It does now, and reads
zero on both. If this check ever fires, compare against the source before suspecting the pipeline.

---

## Known state as of 2026-10-30

Built from a 2026-10-28 backup: 50 products, 23 categories, 4,679 sales, 9,845 lines, 7,009 journal
entries, 51 anonymised customers, 73 anonymised suppliers, 0 users. 4.4 MB.

Six products are deliberately left low on stock so the dashboard's low-stock panel and the reorder
report have content — with everything fully stocked, a prospect never sees two features they are
most likely to care about.

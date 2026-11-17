import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LicenseService, License, DriftRow, FlagChange, RemintOutcome } from '../../services/license.service';
import { ClientService } from '../../services/client.service';
import { licenceKind, LicenceKind, sharedMachineIds } from '../../models/licence-kind';
import { FEATURE_CATALOG } from '../../models/feature-catalog';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/**
 * The two things that exist in the licence table but nowhere in the portal: licences whose
 * signed key no longer matches their client's features, and licences whose client is gone.
 *
 * <p><b>Drift is a preview first and an action second.</b> Reconciling is the ONLY path in the
 * whole portal that may take a feature away from a shop that is currently running it — every
 * other re-mint path is restricted to the flags the save in front of it just changed. So this
 * page shows what each licence would gain and what it would lose, separates the rows that lose
 * something, and names those losses in the confirmation rather than asking a blanket
 * "are you sure".
 *
 * <p>Orphan licences are read-only plus revoke. Adopting one into a new client is deliberately
 * not offered: it would be the first path in the system that creates a client row from a
 * licence, which is a feature of its own, not a button on a cleanup screen.
 */
@Component({
  selector: 'app-licence-drift',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './licence-drift.component.html'
})
export class LicenceDriftComponent implements OnInit {

  rows: DriftRow[] = [];
  loading = true;
  error = '';

  confirming = false;
  reconciling = false;
  outcomes: RemintOutcome[] = [];
  reconciled = false;

  orphans: License[] = [];
  orphansLoading = true;
  sharedMachines: Set<string> | null = null;

  revokeTarget: License | null = null;
  revoking = false;
  revokeError = '';

  constructor(private licenseService: LicenseService,
              private clientService: ClientService,
              public translationService: TranslationService) {}

  ngOnInit(): void {
    this.load();
    this.loadOrphans();
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.licenseService.featuresDrift().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.error = err?.error?.error || this.translationService.instant('driftLoadFailed');
        this.loading = false;
      }
    });
  }

  /**
   * An orphan is a licence whose `clientCode` matches no client row. Six exist in production,
   * all ACTIVE, left behind by deletes that predate the cascade. Every re-mint path and the
   * drift scan skip them — so without this list they appear nowhere in the portal at all.
   */
  private loadOrphans(): void {
    this.orphansLoading = true;
    this.licenseService.getAll().subscribe({
      next: (licences) => {
        this.sharedMachines = sharedMachineIds(licences);
        this.clientService.getAll().subscribe({
          next: (clients) => {
            const codes = new Set(clients.map(c => c.clientCode));
            this.orphans = licences.filter(l => !codes.has(l.clientCode));
            this.orphansLoading = false;
          },
          error: () => { this.orphansLoading = false; }
        });
      },
      error: () => { this.orphansLoading = false; }
    });
  }

  // ── Reading a drift row ──────────────────────────────────────────────────────

  /** What this licence would GAIN: the key says off, the client's features say on. */
  gains(row: DriftRow): FlagChange[] {
    return row.changes.filter(c => c.resolved && !c.inKey);
  }

  /** What this licence would LOSE. These are the rows that need a deliberate decision. */
  losses(row: DriftRow): FlagChange[] {
    return row.changes.filter(c => !c.resolved && c.inKey);
  }

  get losingRows(): DriftRow[] {
    return this.rows.filter(r => this.losses(r).length > 0);
  }

  get gainingOnlyRows(): DriftRow[] {
    return this.rows.filter(r => this.losses(r).length === 0);
  }

  /**
   * The sentence the confirm step shows, built from the LIVE preview rather than from anything
   * written down: which features leave which client, and how many of its licences.
   *
   * <p>Empty when nothing is lost, which is what lets the template ask for a plain confirmation
   * in the harmless case and a named one in the case that actually matters.
   */
  get lossSummary(): string {
    const perClient = new Map<string, { features: Set<string>; licences: number }>();
    for (const row of this.losingRows) {
      const entry = perClient.get(row.clientCode) ?? { features: new Set<string>(), licences: 0 };
      this.losses(row).forEach(c => entry.features.add(c.key));
      entry.licences += 1;
      perClient.set(row.clientCode, entry);
    }
    return [...perClient].map(([code, e]) =>
      `${[...e.features].sort().join(', ')} → ${code} (${e.licences})`).join('  ·  ');
  }

  /**
   * The scan returns rows, not licences, and `licenceKind` needs only these three fields. A
   * drifting row is ACTIVE by construction: the scanner filters on `LicenceLiveness`.
   */
  kindOf(row: DriftRow): LicenceKind {
    return licenceKind(
      { machineId: row.machineId, label: row.label, status: 'ACTIVE' } as License,
      this.sharedMachines);
  }

  kindOfLicence(licence: License): LicenceKind {
    return licenceKind(licence, this.sharedMachines);
  }

  // ── Reconcile ────────────────────────────────────────────────────────────────

  askReconcile(): void {
    this.confirming = true;
    this.error = '';
  }

  cancelReconcile(): void {
    this.confirming = false;
  }

  reconcile(): void {
    if (this.reconciling) return;
    this.reconciling = true;
    this.error = '';
    this.licenseService.reconcileFeaturesDrift().subscribe({
      next: (outcomes) => {
        this.reconciling = false;
        this.confirming = false;
        this.reconciled = true;
        this.outcomes = outcomes;
        // Re-read rather than assuming the list is empty now: a SKIPPED row still drifts, and
        // clearing the list would hide exactly the licences that still need attention.
        this.load();
      },
      error: (err) => {
        this.reconciling = false;
        this.confirming = false;
        this.error = err?.error?.error || this.translationService.instant('driftReconcileFailed');
      }
    });
  }

  get mintedOutcomes(): RemintOutcome[] {
    return this.outcomes.filter(o => o.minted);
  }

  get skippedOutcomes(): RemintOutcome[] {
    return this.outcomes.filter(o => !o.minted);
  }

  // ── Orphan revoke ────────────────────────────────────────────────────────────

  askRevoke(licence: License): void {
    this.revokeTarget = licence;
    this.revokeError = '';
  }

  cancelRevoke(): void {
    this.revokeTarget = null;
  }

  confirmRevoke(): void {
    if (!this.revokeTarget || this.revoking) return;
    this.revoking = true;
    this.licenseService.revoke(this.revokeTarget.id).subscribe({
      next: () => {
        this.revoking = false;
        this.revokeTarget = null;
        this.loadOrphans();
      },
      error: (err) => {
        this.revoking = false;
        this.revokeError = err?.error?.message || this.translationService.instant('revokeFailed');
      }
    });
  }


  /**
   * A feature's human label. The catalogue's own `labelKey` is the only correct source: the
   * translation keys are `featMultiCurrencyPricing`, not the flag name, so building one by
   * concatenation gets the capitalisation wrong and renders the raw key on screen. Falls back to
   * the flag name for anything the catalogue does not list (`quickPickCards` no longer does).
   */
  featureLabelKey(key: string): string {
    return FEATURE_CATALOG.find(f => f.key === key)?.labelKey ?? key;
  }
  truncate(id: string | null, len = 12): string {
    return (id ?? '').slice(0, len);
  }
}

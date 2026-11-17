import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { A11yModule } from '@angular/cdk/a11y';
import { License, LicenseService } from '../../services/license.service';
import { Billing } from '../../models/billing.model';
import { LicenceKind, licenceKind, isLicenceLive } from '../../models/licence-kind';
import { FeatureGridComponent } from '../feature-grid/feature-grid.component';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/**
 * Everything about ONE licence, in a panel that slides in beside the list.
 *
 * <p>A slide-over rather than a modal on purpose: the owner compares a licence against the other
 * licences behind it. A modal would hide exactly the context the comparison needs.
 *
 * <p><b>Deliberately dumb about actions.</b> Renew, revoke, download and revert all have working
 * confirmation flows on the client page already; the drawer raises events and lets that page
 * keep owning them, rather than growing a second copy of four modals. What it does own is the
 * thing that did not exist before: a licence's own feature set, and which flags deviate from the
 * business.
 */
@Component({
  selector: 'app-licence-drawer',
  standalone: true,
  imports: [CommonModule, A11yModule, FeatureGridComponent, TranslatePipe],
  templateUrl: './licence-drawer.component.html'
})
export class LicenceDrawerComponent {

  @Input({ required: true }) licence!: License;
  @Input() sharedMachines: Set<string> | null = null;
  /** This licence's payments, resolved by the page that already loads the billing list. */
  @Input() payments: Billing[] = [];

  @Output() closed = new EventEmitter<void>();
  /** A key was re-minted — the list behind should refresh. Carries the updated licence. */
  @Output() changed = new EventEmitter<License>();
  @Output() renew = new EventEmitter<License>();
  @Output() revoke = new EventEmitter<License>();
  @Output() downloadDat = new EventEmitter<License>();
  @Output() revertReissue = new EventEmitter<License>();

  saving = false;
  /** Why the last save did not reach the shop, if it did not. A fixed code from the server. */
  skippedReason: string | null = null;
  error: string | null = null;

  constructor(private licenseService: LicenseService,
              public translationService: TranslationService) {}

  get kind(): LicenceKind { return licenceKind(this.licence, this.sharedMachines); }
  get live(): boolean { return isLicenceLive(this.licence); }

  /**
   * The licence's EFFECTIVE flags. Null means the caller loaded this licence from the
   * whole-fleet endpoint, which omits them — not that the licence grants nothing.
   */
  get effectiveFeatures(): Record<string, boolean> | null {
    return this.licence.effectiveFeatures ?? null;
  }

  get overriddenKeys(): string[] {
    return Object.keys(this.licence.featureOverride ?? {});
  }

  get paidTotal(): number {
    return this.payments.reduce((sum, b) => sum + (b.amount ? +b.amount : 0), 0);
  }

  /** Turn a flag on or off for THIS licence alone. */
  onToggle(change: { key: string; value: boolean }): void {
    this.patch({ [change.key]: change.value });
  }

  /** Stop overriding a flag — it follows the business again. */
  onResetToInherited(key: string): void {
    this.patch({ [key]: null });
  }

  /**
   * Sends one key at a time, and renders the server's answer rather than its own guess: the
   * response carries the resolved effective set, so the drawer cannot disagree with what was
   * actually stored.
   */
  private patch(body: Record<string, boolean | null>): void {
    if (this.saving) return;
    this.saving = true;
    this.skippedReason = null;
    this.error = null;
    this.licenseService.setFeatures(this.licence.id, body).subscribe({
      next: (res) => {
        this.saving = false;
        this.licence = {
          ...this.licence,
          effectiveFeatures: res.effectiveFeatures,
          featureOverride: res.featureOverride
        };
        // Saved either way; `reminted: false` means the KEY did not go out, which the shop
        // needs to know about separately from the save having worked.
        this.skippedReason = res.reminted ? null : res.skippedReason;
        this.changed.emit(this.licence);
      },
      error: (err) => {
        this.saving = false;
        this.error = err?.error?.error ?? this.translationService.instant('licenceFeatureSaveFailed');
      }
    });
  }

  /** A fixed server code turned into something a person can act on. */
  skippedReasonLabel(reason: string): string {
    const key = {
      'expired': 'remintSkippedExpired',
      'revoked': 'remintSkippedRevoked',
      'no client': 'remintSkippedNoClient',
      'v1 key - re-issue first': 'remintSkippedV1',
      'drifting - reconcile first': 'remintSkippedDrifting',
      'unverifiable key': 'remintSkippedUnverifiable',
      'resign failed': 'remintSkippedResignFailed'
    }[reason];
    return key ? this.translationService.instant(key) : reason;
  }
}

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { License } from '../../services/license.service';
import { LicenceKind, licenceKind, isLicenceLive } from '../../models/licence-kind';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/**
 * One licence, as a card on the client page. Clicking it opens the drawer.
 *
 * <p>This is the piece that makes the portal's structure say Client → Licence → Features: a
 * client HAS licences, and a licence is the thing a shop actually runs. Before this they were a
 * table on a tab of their own, which hid the relationship entirely.
 */
@Component({
  selector: 'app-licence-card',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './licence-card.component.html'
})
export class LicenceCardComponent {

  @Input({ required: true }) licence!: License;

  /** Machine ids serving more than one client — see models/licence-kind.ts. */
  @Input() sharedMachines: Set<string> | null = null;

  /** This licence's own payments, already totalled by the page that owns the billing list. */
  @Input() paidTotal = 0;

  @Output() open = new EventEmitter<License>();

  constructor(public translationService: TranslationService) {}

  get kind(): LicenceKind {
    return licenceKind(this.licence, this.sharedMachines);
  }

  get live(): boolean {
    return isLicenceLive(this.licence);
  }

  /** Short enough to tell two machines apart, long enough not to collide. */
  get shortMachineId(): string {
    return (this.licence.machineId ?? '').slice(0, 12);
  }

  /**
   * Days until expiry; null for a perpetual licence (most of the fleet) and negative once it has
   * passed, which the template shows as the danger tone.
   */
  get daysLeft(): number | null {
    if (!this.licence.expiresAt) return null;
    const expiry = new Date(this.licence.expiresAt + 'T00:00:00').getTime();
    return Math.ceil((expiry - new Date().setHours(0, 0, 0, 0)) / 86400000);
  }

  get expiryTone(): string {
    const d = this.daysLeft;
    if (d === null) return 'status-pill';
    if (d < 0) return 'status-pill status-pill--danger';
    if (d <= 30) return 'status-pill status-pill--warning';
    return 'status-pill status-pill--success';
  }

  /**
   * How many flags this licence overrides.
   *
   * <p><b>`null` is not zero.</b> The whole-fleet listing returns the maps as null on purpose —
   * resolving entitlements for 105 rows would be a client lookup each — so a card built from
   * that list must say "not loaded" rather than "same as business", which would be a confident
   * lie about what the shop is running.
   */
  get overrideCount(): number | null {
    const override = this.licence.featureOverride;
    if (override === null || override === undefined) return null;
    return Object.keys(override).length;
  }

  get featuresSummary(): string {
    const count = this.overrideCount;
    if (count === null) return this.translationService.instant('featuresNotLoaded');
    if (count === 0) return this.translationService.instant('featureInherited');
    return `${this.translationService.instant('featureCustomised')} (${count})`;
  }
}

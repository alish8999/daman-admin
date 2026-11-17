import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FEATURE_CATALOG, FEATURE_GROUP_ORDER, FeatureGroup } from '../../models/feature-catalog';
import { FeaturePriceService } from '../../services/feature-price.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/**
 * The feature catalogue, as a grid of toggles. **One component, two callers.**
 *
 * <p>`business` mode is the client's default set — priced, with the enabled count and the
 * running `$` total. `licence` mode is one licence's EFFECTIVE set, where each flag is either
 * inherited from the business or overridden for this licence alone, and can be reset back.
 *
 * <p><b>Driven by a plain map, not a FormGroup</b>, even though its only caller today is a
 * reactive form. The licence drawer has no form at all, and a component that could only be fed
 * by `formControlName` would have to be written twice — which is exactly the duplication this
 * extraction exists to prevent. The form stays the source of truth in the client page; it just
 * bridges through `features` / `toggle`.
 */
@Component({
  selector: 'app-feature-grid',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './feature-grid.component.html'
})
export class FeatureGridComponent {

  /** The flags to render, by catalogue key. In licence mode this is the EFFECTIVE set. */
  @Input() features: Record<string, boolean> = {};

  /** 'business' = the client default (priced, with totals); 'licence' = one licence's set. */
  @Input() mode: 'business' | 'licence' = 'business';

  /** licence mode only: which keys deviate from the business default. */
  @Input() overriddenKeys: string[] = [];

  @Input() disabled = false;

  @Output() toggle = new EventEmitter<{ key: string; value: boolean }>();

  /** licence mode only: stop overriding this key and follow the business again. */
  @Output() resetToInherited = new EventEmitter<string>();

  readonly featureCatalog = FEATURE_CATALOG;
  readonly featureGroupOrder = FEATURE_GROUP_ORDER;

  constructor(public featurePrices: FeaturePriceService,
              public translationService: TranslationService) {
    this.featurePrices.load();
  }

  featureGroupEntries(group: FeatureGroup) {
    return this.featureCatalog.filter(f => f.group === group);
  }

  isOn(key: string): boolean {
    return !!this.features?.[key];
  }

  isOverridden(key: string): boolean {
    return this.mode === 'licence' && this.overriddenKeys.includes(key);
  }

  onToggle(key: string, value: boolean): void {
    if (this.disabled) return;
    this.toggle.emit({ key, value });
  }

  /** Badge text for a feature: 'free', a $ price, or null when it carries no price at all. */
  featureBadge(key: string): string | null {
    const price = this.featurePrices.priceOf(key);
    if (price === null) return null;
    return price === 0 ? 'free' : `$${price}`;
  }

  get enabledAddonCount(): number {
    return this.featureCatalog
      .filter(f => f.group === 'addons' && this.featurePrices.isPaid(f.key) && this.isOn(f.key))
      .length;
  }

  get addonTotalValue(): number {
    return this.featurePrices.addonValue(this.features as Record<string, boolean | undefined>);
  }
}

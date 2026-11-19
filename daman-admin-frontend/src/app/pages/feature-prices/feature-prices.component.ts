import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CatalogFeature, FeaturePriceService } from '../../services/feature-price.service';
import { FEATURE_CATALOG } from '../../models/feature-catalog';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/** One row on screen: what is stored, plus what the owner has typed but not yet saved. */
interface PriceRow {
  key: string;
  labelKey: string | null;
  stored: CatalogFeature;
  price: number | null;
  contactUs: boolean;
  saving: boolean;
  savedAt: number | null;
  error: string;
}

/**
 * Where the owner sets what each add-on costs.
 *
 * <p><b>What this replaced.</b> The prices were a constant list in the admin backend's Java:
 * changing one meant an edit and a redeploy. That was accepted while there was one copy of the
 * figures. By 2026-11-19 there were three, and two had drifted — the shipped POS fallback was
 * missing Mobile POS entirely, and the website's tutorial pages were quoting $39 for Product
 * Recipes while the app quoted $25. One editable row each, and the rest generated.
 *
 * <p><b>Saved one row at a time, not with a Save button over the whole table.</b> A price is an
 * independent fact; a single submit would make a typo in one row either block the others or ship
 * with them.
 *
 * <p><b>This must be done on the LIVE portal.</b> Shops read prices from
 * {@code admin-api.damansoft.com}, so a price edited on a developer's localhost reaches nobody.
 * The page says so rather than leaving it to be discovered.
 */
@Component({
  selector: 'app-feature-prices',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslatePipe],
  templateUrl: './feature-prices.component.html'
})
export class FeaturePricesComponent implements OnInit {

  /**
   * Names for the sellable things that are NOT feature flags, so FEATURE_CATALOG has no card
   * for them: a new branch, and hosting the shop in the cloud. Without this the screen would
   * print their raw keys.
   */
  private static readonly OFF_CATALOGUE: Record<string, string> = {
    newBranch: 'featNewBranch',
    cloudHosting: 'featCloudHosting'
  };

  rows: PriceRow[] = [];
  loading = true;
  loadError = '';

  constructor(private prices: FeaturePriceService,
              public translationService: TranslationService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    this.prices.listForEditing().subscribe({
      next: (res) => {
        this.rows = (res?.features ?? []).map(f => this.toRow(f));
        this.loading = false;
      },
      error: () => {
        this.loadError = this.translationService.instant('featurePricesLoadFailed');
        this.loading = false;
      }
    });
  }

  private toRow(f: CatalogFeature): PriceRow {
    return {
      key: f.key,
      // newBranch is not a ClientFeatures flag and so has no catalogue card; it falls back to
      // its own key rather than rendering a blank name.
      labelKey: FEATURE_CATALOG.find(e => e.key === (f.key as any))?.labelKey
             ?? FeaturePricesComponent.OFF_CATALOGUE[f.key]
             ?? null,
      stored: f,
      price: f.price,
      contactUs: f.contactUs,
      saving: false,
      savedAt: null,
      error: ''
    };
  }

  /** Nothing to save until the figure or the on-enquiry flag actually differs from the row. */
  isDirty(row: PriceRow): boolean {
    if (row.contactUs !== row.stored.contactUs) return true;
    if (row.contactUs) return false;              // the figure is irrelevant while on enquiry
    return (row.price ?? null) !== (row.stored.price ?? null);
  }

  canSave(row: PriceRow): boolean {
    if (row.saving || !this.isDirty(row)) return false;
    if (row.contactUs) return true;
    return typeof row.price === 'number' && row.price >= 0;
  }

  save(row: PriceRow): void {
    if (!this.canSave(row)) return;
    row.saving = true;
    row.error = '';
    this.prices.setPrice(row.key, row.contactUs ? null : row.price, row.contactUs, row.stored.bundledWith)
      .subscribe({
        next: (saved) => {
          row.saving = false;
          // The server decides — it clears the figure when contactUs is set — so the row is
          // reset from the response rather than from what was typed.
          row.stored = saved;
          row.price = saved.price;
          row.contactUs = saved.contactUs;
          row.savedAt = Date.now();
        },
        error: (err) => {
          row.saving = false;
          row.error = err?.error?.error || this.translationService.instant('saveFailed');
        }
      });
  }

  revert(row: PriceRow): void {
    row.price = row.stored.price;
    row.contactUs = row.stored.contactUs;
    row.error = '';
  }

  /** The prerequisite's own name, for the one add-on priced as an increment. */
  prerequisiteLabelKey(row: PriceRow): string | null {
    if (!row.stored.bundledWith) return null;
    return FEATURE_CATALOG.find(e => e.key === (row.stored.bundledWith as any))?.labelKey
        ?? row.stored.bundledWith;
  }

  justSaved(row: PriceRow): boolean {
    return row.savedAt !== null && Date.now() - row.savedAt < 4000;
  }
}

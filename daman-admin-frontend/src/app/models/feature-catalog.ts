import { ClientFeatures } from './client-config.model';

export type FeatureGroup = 'core' | 'operations' | 'reports' | 'addons';

export interface FeatureCatalogEntry {
  /** Matches a boolean key on ClientFeatures. */
  key: keyof ClientFeatures;
  group: FeatureGroup;
  /** Bootstrap Icons class suffix, e.g. 'bi-translate'. */
  icon: string;
  /** USD price for this add-on. 0 = free/included add-on. null = bundled core feature (no price shown). */
  price: number | null;
  /** i18n key suffix for the card title, e.g. featMultiLanguage. */
  labelKey: string;
  /** i18n key suffix for the one-line description, e.g. featMultiLanguageDesc. */
  descKey: string;
}

/**
 * Single source of truth for every toggleable client feature, replacing the old
 * BASIC/PRO/ULTIMATE package-definition bundles. Mirrors the pricing shown on the
 * client-facing upsell page (daman-frontend/features.component.html) for the
 * `addons` group; everything else is bundled into the one shipped app version.
 */
export const FEATURE_CATALOG: FeatureCatalogEntry[] = [
  // ── Core (bundled, always available in the one app version) ─────────────
  { key: 'multiLanguage',  group: 'core', icon: 'bi-translate',         price: null, labelKey: 'featMultiLanguage',  descKey: 'featMultiLanguageDesc' },
  { key: 'barcode',        group: 'core', icon: 'bi-upc-scan',          price: null, labelKey: 'featBarcode',        descKey: 'featBarcodeDesc' },
  { key: 'reports',        group: 'core', icon: 'bi-bar-chart-line',    price: null, labelKey: 'featReports',        descKey: 'featReportsDesc' },
  { key: 'suppliers',      group: 'core', icon: 'bi-truck',             price: null, labelKey: 'featSuppliers',      descKey: 'featSuppliersDesc' },
  { key: 'multiCurrency',  group: 'core', icon: 'bi-currency-exchange', price: null, labelKey: 'featMultiCurrency',  descKey: 'featMultiCurrencyDesc' },
  { key: 'clientLedger',   group: 'core', icon: 'bi-person-lines-fill', price: null, labelKey: 'featClientLedger',   descKey: 'featClientLedgerDesc' },
  { key: 'supplierLedger', group: 'core', icon: 'bi-building',          price: null, labelKey: 'featSupplierLedger', descKey: 'featSupplierLedgerDesc' },

  // ── Operations & inventory ────────────────────────────────────────────
  { key: 'fractionalQuantity',   group: 'operations', icon: 'bi-rulers',           price: null, labelKey: 'featFractionalQuantity',   descKey: 'featFractionalQuantityDesc' },
  { key: 'multiCurrencyPricing', group: 'operations', icon: 'bi-cash-coin',        price: null, labelKey: 'featMultiCurrencyPricing', descKey: 'featMultiCurrencyPricingDesc' },
  { key: 'batchStocktake',       group: 'operations', icon: 'bi-clipboard-check',  price: null, labelKey: 'featBatchStocktake',       descKey: 'featBatchStocktakeDesc' },
  { key: 'bulkPriceUpdate',      group: 'operations', icon: 'bi-tags',             price: null, labelKey: 'featBulkPriceUpdate',      descKey: 'featBulkPriceUpdateDesc' },
  { key: 'seedDemoData',         group: 'operations', icon: 'bi-flower1',          price: null, labelKey: 'featSeedDemoData',         descKey: 'featSeedDemoDataDesc' },
  { key: 'scaleBarcodes',        group: 'operations', icon: 'bi-upc',              price: null, labelKey: 'featScaleBarcodes',        descKey: 'featScaleBarcodesDesc' },

  // ── Reports & statements ─────────────────────────────────────────────
  { key: 'accountStatement', group: 'reports', icon: 'bi-file-earmark-text', price: null, labelKey: 'featAccountStatement', descKey: 'featAccountStatementDesc' },
  { key: 'itemLedger',       group: 'reports', icon: 'bi-journal-text',      price: null, labelKey: 'featItemLedger',       descKey: 'featItemLedgerDesc' },

  // ── Paid add-ons (priced individually, matches features.component.html) ─
  { key: 'invoiceSettings', group: 'addons', icon: 'bi-receipt-cutoff',       price: 0,  labelKey: 'featInvoiceSettings', descKey: 'featInvoiceSettingsDesc' },
  { key: 'autoBackup',      group: 'addons', icon: 'bi-cloud-arrow-down',     price: 0,  labelKey: 'featAutoBackup',      descKey: 'featAutoBackupDesc' },
  { key: 'quotation',       group: 'addons', icon: 'bi-file-earmark-ruled',   price: 15, labelKey: 'featQuotation',       descKey: 'featQuotationDesc' },
  { key: 'userManagement',  group: 'addons', icon: 'bi-people',               price: 20, labelKey: 'featUserManagement',  descKey: 'featUserManagementDesc' },
  { key: 'shifts',          group: 'addons', icon: 'bi-clock-history',        price: 29, labelKey: 'featShifts',          descKey: 'featShiftsDesc' },
  { key: 'posTerminals',    group: 'addons', icon: 'bi-window-stack',         price: 29, labelKey: 'featPosTerminals',    descKey: 'featPosTerminalsDesc' },
  { key: 'accounting',      group: 'addons', icon: 'bi-journal-bookmark',     price: 39, labelKey: 'featAccounting',      descKey: 'featAccountingDesc' },
  { key: 'productRecipes',  group: 'addons', icon: 'bi-cup-hot',              price: 39, labelKey: 'featProductRecipes',  descKey: 'featProductRecipesDesc' },
  // Manufacturing requires productRecipes (BOM) to function, so its price is the
  // marginal $20 on top of BOM's $39 — enabling both totals the $59 bundle price
  // shown on the client-facing features page, rather than double-charging $39+$39.
  { key: 'manufacturing',   group: 'addons', icon: 'bi-gear-wide-connected',  price: 20, labelKey: 'featManufacturing',   descKey: 'featManufacturingDesc' },
];

export const FEATURE_GROUP_ORDER: FeatureGroup[] = ['core', 'operations', 'reports', 'addons'];

/** Sum of prices for the given enabled feature keys (0 counts as free, null is excluded). */
export function addonValue(enabled: Partial<Record<keyof ClientFeatures, boolean>>): number {
  return FEATURE_CATALOG.reduce((sum, f) => {
    if (f.price && enabled[f.key]) return sum + f.price;
    return sum;
  }, 0);
}

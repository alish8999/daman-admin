import { ClientFeatures } from './client-config.model';

export type FeatureGroup = 'core' | 'operations' | 'reports' | 'addons' | 'developer';

export interface FeatureCatalogEntry {
  /** Matches a boolean key on ClientFeatures. */
  key: keyof ClientFeatures;
  group: FeatureGroup;
  /** Bootstrap Icons class suffix, e.g. 'bi-translate'. */
  icon: string;
  // `price` was removed on 2026-10-30. It made this file one of three disagreeing price lists —
  // it said Shifts was $25 while the POS upsell modal said $29 and the POS features page's own
  // comments said something else again. Prices now come from FeatureCatalog in the admin backend,
  // read through FeaturePriceService, and are served to shipped POS installs from the same
  // constant. Whether a feature is a paid add-on is now `featurePrices.isPaid(key)`, not a
  // truthiness check on a field here.
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
  { key: 'multiLanguage',  group: 'core', icon: 'bi-translate', labelKey: 'featMultiLanguage',  descKey: 'featMultiLanguageDesc' },
  { key: 'barcode',        group: 'core', icon: 'bi-upc-scan', labelKey: 'featBarcode',        descKey: 'featBarcodeDesc' },
  { key: 'reports',        group: 'core', icon: 'bi-bar-chart-line', labelKey: 'featReports',        descKey: 'featReportsDesc' },
  { key: 'suppliers',      group: 'core', icon: 'bi-truck', labelKey: 'featSuppliers',      descKey: 'featSuppliersDesc' },
  { key: 'multiCurrency',  group: 'core', icon: 'bi-currency-exchange', labelKey: 'featMultiCurrency',  descKey: 'featMultiCurrencyDesc' },
  { key: 'clientLedger',   group: 'core', icon: 'bi-person-lines-fill', labelKey: 'featClientLedger',   descKey: 'featClientLedgerDesc' },
  { key: 'supplierLedger', group: 'core', icon: 'bi-building', labelKey: 'featSupplierLedger', descKey: 'featSupplierLedgerDesc' },

  // ── Operations & inventory ────────────────────────────────────────────
  { key: 'fractionalQuantity',   group: 'operations', icon: 'bi-rulers', labelKey: 'featFractionalQuantity',   descKey: 'featFractionalQuantityDesc' },
  { key: 'multiCurrencyPricing', group: 'operations', icon: 'bi-cash-coin', labelKey: 'featMultiCurrencyPricing', descKey: 'featMultiCurrencyPricingDesc' },
  { key: 'batchStocktake',       group: 'operations', icon: 'bi-clipboard-check', labelKey: 'featBatchStocktake',       descKey: 'featBatchStocktakeDesc' },
  { key: 'bulkPriceUpdate',      group: 'operations', icon: 'bi-tags', labelKey: 'featBulkPriceUpdate',      descKey: 'featBulkPriceUpdateDesc' },
  { key: 'seedDemoData',         group: 'operations', icon: 'bi-flower1', labelKey: 'featSeedDemoData',         descKey: 'featSeedDemoDataDesc' },
  { key: 'scaleBarcodes',        group: 'operations', icon: 'bi-upc', labelKey: 'featScaleBarcodes',        descKey: 'featScaleBarcodesDesc' },

  // ── Reports & statements ─────────────────────────────────────────────
  { key: 'accountStatement', group: 'reports', icon: 'bi-file-earmark-text', labelKey: 'featAccountStatement', descKey: 'featAccountStatementDesc' },
  { key: 'itemLedger',       group: 'reports', icon: 'bi-journal-text', labelKey: 'featItemLedger',       descKey: 'featItemLedgerDesc' },

  // ── Paid add-ons (priced individually, matches features.component.html) ─
  { key: 'invoiceSettings', group: 'addons', icon: 'bi-receipt-cutoff',  labelKey: 'featInvoiceSettings', descKey: 'featInvoiceSettingsDesc' },
  { key: 'autoBackup',      group: 'addons', icon: 'bi-cloud-arrow-down',  labelKey: 'featAutoBackup',      descKey: 'featAutoBackupDesc' },
  { key: 'quotation',       group: 'addons', icon: 'bi-file-earmark-ruled', labelKey: 'featQuotation',       descKey: 'featQuotationDesc' },
  { key: 'userManagement',  group: 'addons', icon: 'bi-people', labelKey: 'featUserManagement',  descKey: 'featUserManagementDesc' },
  { key: 'shifts',          group: 'addons', icon: 'bi-clock-history', labelKey: 'featShifts',          descKey: 'featShiftsDesc' },
  { key: 'posTerminals',    group: 'addons', icon: 'bi-window-stack', labelKey: 'featPosTerminals',    descKey: 'featPosTerminalsDesc' },
  { key: 'tableOrders',     group: 'addons', icon: 'bi-grid-3x3-gap', labelKey: 'featTableOrders',     descKey: 'featTableOrdersDesc' },
  // quickPickCards (Quick Sale, was a $15 add-on) was removed from the catalog
  // on 2026-10-19: it is now free on every installation and the store owner
  // toggles it in the POS app's own Settings, so there is nothing to sell or
  // tick here. Dropping the entry also takes it out of the add-on value totals
  // and the Clients-page chips, which is correct. The flag is still carried by
  // ClientFeatures / the licence payload for backward compatibility, but the
  // desktop overlays it from app_settings['ui.quickSale'] and ignores whatever
  // the licence says — see QuickSaleSettingsService in daman-backend.
  { key: 'cafeMode',        group: 'addons', icon: 'bi-shop',  labelKey: 'featCafeMode',        descKey: 'featCafeModeDesc' },
  { key: 'accounting',      group: 'addons', icon: 'bi-journal-bookmark', labelKey: 'featAccounting',      descKey: 'featAccountingDesc' },
  { key: 'currencyExchange', group: 'addons', icon: 'bi-arrow-left-right', labelKey: 'featCurrencyExchange', descKey: 'featCurrencyExchangeDesc' },
  { key: 'kitchenPrinter',  group: 'addons', icon: 'bi-printer', labelKey: 'featKitchenPrinter',   descKey: 'featKitchenPrinterDesc' },
  { key: 'productRecipes',  group: 'addons', icon: 'bi-cup-hot', labelKey: 'featProductRecipes',  descKey: 'featProductRecipesDesc' },
  // Manufacturing requires productRecipes (BOM) to function, so its price is the
  // marginal $25 on top of BOM's $25 — enabling both totals the $50 bundle price
  // shown on the client-facing features page, rather than double-charging $25+$25.
  { key: 'manufacturing',   group: 'addons', icon: 'bi-gear-wide-connected', labelKey: 'featManufacturing',   descKey: 'featManufacturingDesc' },
  { key: 'consignment',     group: 'addons', icon: 'bi-truck-flatbed', labelKey: 'featConsignment',     descKey: 'featConsignmentDesc' },
  { key: 'shareholders',    group: 'addons', icon: 'bi-person-badge', labelKey: 'featShareholders',   descKey: 'featShareholdersDesc' },
  { key: 'fixedAssets',     group: 'addons', icon: 'bi-building',  labelKey: 'featFixedAssets',    descKey: 'featFixedAssetsDesc' },
  { key: 'deviceRepair',    group: 'addons', icon: 'bi-tools', labelKey: 'featDeviceRepair',    descKey: 'featDeviceRepairDesc' },
  { key: 'googleDriveBackup', group: 'addons', icon: 'bi-cloud-upload', labelKey: 'featGoogleDriveBackup', descKey: 'featGoogleDriveBackupDesc' },

  // ── Developer / testing (not a purchasable client feature) ──────────────
  { key: 'simulatePosMode', group: 'developer', icon: 'bi-window-fullscreen', labelKey: 'featSimulatePosMode', descKey: 'featSimulatePosModeDesc' },
];

export const FEATURE_GROUP_ORDER: FeatureGroup[] = ['core', 'operations', 'reports', 'addons', 'developer'];

// addonValue() moved to FeaturePriceService.addonValue() on 2026-10-30, when prices left this
// file. The bundle semantics are unchanged: manufacturing is priced at its MARGINAL cost on top
// of productRecipes, so a client holding both still totals the $50 bundle rather than $25 twice.

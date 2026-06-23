export interface PackageDef {
  /** Stable key — also the i18n suffix (package.basic / package.pro / ...). */
  key: string;
  /** Feature keys enabled by this package. */
  features: string[];
}

export interface PackageDefinitions {
  packages: PackageDef[];
}

/**
 * Editable feature keys that can belong to a package, in canonical display order.
 * `seedDemoData` is intentionally excluded — it's a one-time dev/demo toggle,
 * not a tier feature.
 */
export const PACKAGE_FEATURE_KEYS: readonly string[] = [
  'multiLanguage', 'barcode', 'reports', 'suppliers', 'multiCurrency',
  'shifts', 'clientLedger', 'supplierLedger', 'fractionalQuantity', 'multiCurrencyPricing',
  'accountStatement', 'itemLedger', 'batchStocktake', 'bulkPriceUpdate', 'productRecipes',
  'manufacturing'
];

/** Fallback used when the API is unreachable — mirrors the backend seed defaults. */
export const DEFAULT_PACKAGE_DEFINITIONS: PackageDefinitions = {
  packages: [
    {
      key: 'basic',
      features: ['multiLanguage', 'suppliers', 'clientLedger', 'supplierLedger', 'fractionalQuantity']
    },
    {
      key: 'pro',
      features: ['multiLanguage', 'suppliers', 'clientLedger', 'supplierLedger', 'fractionalQuantity',
        'barcode', 'reports', 'multiCurrency',
        'accountStatement', 'itemLedger', 'batchStocktake', 'bulkPriceUpdate', 'multiCurrencyPricing']
    },
    {
      key: 'ultimate',
      features: ['multiLanguage', 'suppliers', 'clientLedger', 'supplierLedger', 'fractionalQuantity',
        'barcode', 'reports', 'multiCurrency',
        'accountStatement', 'itemLedger', 'batchStocktake', 'bulkPriceUpdate', 'multiCurrencyPricing',
        'shifts', 'productRecipes', 'manufacturing']
    }
  ]
};

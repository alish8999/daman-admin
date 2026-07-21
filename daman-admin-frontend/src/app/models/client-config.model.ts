export interface ClientFeatures {
  multiLanguage: boolean;
  barcode: boolean;
  reports: boolean;
  suppliers?: boolean;
  seedDemoData?: boolean;
  multiCurrency?: boolean;
  shifts?: boolean;
  clientLedger?: boolean;
  supplierLedger?: boolean;
  /** When false, only whole-number quantities are accepted at the POS. */
  fractionalQuantity?: boolean;
  /**
   * Per-product pricing-currency override — lets a single SKU lock its price
   * in a non-base currency (e.g. MTN top-up cards at exactly 2 500 SYP even
   * when the system base is USD). Default: false — opt-in per client.
   */
  multiCurrencyPricing?: boolean;
  /**
   * Shows the Client/Supplier Account Statement report page (nav link + route).
   * Default: false — opt-in per client.
   */
  accountStatement?: boolean;
  /**
   * Shows the per-item Item Ledger report page (nav link + route).
   * Default: false — opt-in per client.
   */
  itemLedger?: boolean;
  /**
   * Batch stocktake / inventory reconciliation page (تسوية الجرد المجمّعة).
   * Adds the bulk "actual vs system qty" reconciliation page and unlocks the
   * MONTHLY_RECONCILIATION reason in P&L wastage / inventory-gain reports.
   * Default: false — opt-in per client.
   */
  batchStocktake?: boolean;
  /**
   * Bulk category price update (تعديل أسعار قسم بالكامل) on the Products page.
   * Default: false — opt-in per client via the admin dashboard.
   */
  bulkPriceUpdate?: boolean;
  /**
   * BOM / recipe products (F&B). Finished goods backflush raw materials on sale.
   * Default: false — opt-in per client via admin dashboard.
   */
  productRecipes?: boolean;
  /**
   * Manufacturing Orders (batch / make-to-stock production). Lets recipe products
   * be pre-produced to stock and sold from finished-good inventory instead of
   * exploding the BOM at sale time. Default: false — opt-in per client.
   */
  manufacturing?: boolean;
  /**
   * User & Permissions management in Settings — lets the admin assign per-user
   * permission flags. Default: false — opt-in per client (ultimate tier).
   */
  userManagement?: boolean;
  /**
   * Invoice Settings card in Settings — configure contact details printed on
   * every invoice. Default: false — opt-in per client (pro tier).
   */
  invoiceSettings?: boolean;
  /** Quotations / price-quote workflow (عروض الأسعار). Default: false — opt-in per client (pro tier). */
  quotation?: boolean;
  /** Accounting module — double-entry ledger, P&L, Balance Sheet, journal entries. Default: false — opt-in per client ($39 add-on). */
  accounting?: boolean;
  /**
   * Variable-weight scale barcode support (CAS/Rongta/Aclas/Bizerba-style
   * EAN-13 with an embedded PLU + weight/price). Default: false — opt-in
   * per client, relevant mainly to grocery/nuts-dairy style stores with
   * actual scale hardware. See Docs/SCALE_BARCODE_PLAN.md.
   */
  scaleBarcodes?: boolean;
  /**
   * Automatic daily local backup of the desktop SQLite database. Free — like
   * invoiceSettings, shown with a $0 badge, not a paid tier. Default: true
   * (unlike every other flag here) so existing clients don't lose backup
   * protection the moment this ships.
   */
  autoBackup?: boolean;
}

export interface ClientConfig {
  id?: number;
  clientCode: string;
  appName: string;
  tagline: string;
  logoDark: string;
  logoLight: string;
  favicon: string;
  colorPrimary: string;
  colorSecondary: string;
  colorSuccess: string;
  colorDanger: string;
  colorWarning: string;
  colorInfo: string;
  footerDeveloper: string;
  footerUrl: string;
  /** Store type for demo data seeder selection. */
  storeType?: string;
  /** Base currency for all financial records. One of: USD, SYP, SYP_OLD. */
  baseCurrency?: string;
  /** Optional data-URL or asset path for the dashboard header background image. */
  dashboardHeaderImage?: string;
  adminUsername: string;
  adminPassword: string;
  phone?: string;
  email?: string;
  pointOfContact?: string;
  defaultBuildTarget?: string;
  features?: ClientFeatures;
  createdAt?: string;
  updatedAt?: string;

  // Client status (billing details live in the Billing table)
  clientStatus?: 'ACTIVE' | 'TRIAL' | 'DUMMY' | null;
  clientNotes?: string | null;
}

export interface ClientConfigExport {
  appName: string;
  tagline: string;
  logo: { dark: string; light: string };
  favicon: string;
  colors: {
    primary: string;
    secondary: string;
    success: string;
    danger: string;
    warning: string;
    info: string;
  };
  footer: { developer: string; url: string };
  storeType?: string;
  baseCurrency?: string;
  dashboard?: { headerImage?: string };
  features?: ClientFeatures;
}

export interface BuildStatus {
  clientCode: string;
  platform: string;
  status: 'IDLE' | 'BUILDING' | 'SUCCESS' | 'FAILED';
  startedAt?: string;
  finishedAt?: string;
  artifactPath?: string;
  artifactName?: string;
  logs: string[];
}

export interface BuildLogEntry {
  id: number;
  clientCode: string;
  platform: string;
  status: string;
  artifactName?: string;
  startedAt: string;
  finishedAt?: string;
  durationSeconds?: number;
}

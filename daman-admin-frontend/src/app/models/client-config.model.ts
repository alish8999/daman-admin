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

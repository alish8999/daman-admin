import { Component, OnInit, OnDestroy, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClientService } from '../../services/client.service';
import { LicenseService, License } from '../../services/license.service';
import { BillingService } from '../../services/billing.service';
import { Billing } from '../../models/billing.model';
import { AppVersionService, AppVersion } from '../../services/app-version.service';
import { ClientConfig, BuildStatus, BuildLogEntry } from '../../models/client-config.model';
import { addonValue } from '../../models/feature-catalog';
import { computeClientStatus, ClientStatusResult } from '../../models/client-status';

@Component({
  selector: 'app-clients',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './clients.component.html',
  styles: [`
    .build-log {
      background: #1e1e1e;
      color: #d4d4d4;
      font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
      font-size: 0.8rem;
      line-height: 1.5;
      min-height: 300px;
      max-height: 500px;
      overflow-y: auto;
      white-space: pre-wrap;
      word-break: break-all;
      direction: ltr;
      text-align: left;
    }
    .feature-badge {
      font-size: 0.7rem;
      padding: 0.2em 0.5em;
    }
    .clients-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
      gap: 1.1rem;
    }
    .client-card {
      background: #fff;
      border-radius: 12px;
      border-inline-start: 4px solid transparent;
      box-shadow: 0 2px 8px rgba(0,0,0,0.08);
      overflow: hidden;
      display: flex;
      flex-direction: column;
      transition: box-shadow 0.18s, transform 0.18s;
    }
    .client-card:hover {
      box-shadow: 0 6px 20px rgba(0,0,0,0.13);
      transform: translateY(-2px);
    }
    .client-card--active {
      background: #b8e4c6;
      border-inline-start-color: #16a34a;
    }
    .client-card--active .client-card-footer {
      background: #e8faf0;
      border-top-color: #d7f3e2;
    }
    .client-card-header {
      padding: 14px 16px 12px;
    }
    .client-app-name {
      font-size: 1rem;
      font-weight: 700;
      text-shadow: 0 1px 3px rgba(0,0,0,0.25);
      line-height: 1.2;
    }
    .client-tagline {
      font-size: 0.78rem;
      opacity: 0.82;
      margin-top: 4px;
      line-height: 1.3;
    }
    .client-card-body {
      padding: 10px 16px 8px;
      flex: 1;
    }
    .client-card-footer {
      padding: 10px 14px;
      border-top: 1px solid #f0f0f0;
      background: #fafafa;
    }
    .color-swatch {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      display: inline-block;
      border: 1px solid rgba(0,0,0,0.12);
    }
    .license-row {
      border-top: 1px dashed #e8e8e8;
      margin-top: 8px;
      padding-top: 7px;
    }
  `]
})
export class ClientsComponent implements OnInit, OnDestroy {
  clients: ClientConfig[] = [];
  licenses: License[] = [];
  billings: Billing[] = [];
  versions: AppVersion[] = [];

  // Search & filter
  searchQuery = '';
  licenseFilter: 'all' | 'active' | 'revoked' | 'none' = 'all';
  expirationFilter: 'all' | 'expiring' | 'expired' | 'never' = 'all';
  statusFilter: 'all' | 'ACTIVE' | 'TRIAL' | 'DUMMY' = 'all';

  /** Client status is computed from license + billing data — see models/client-status.ts. */
  statusOf(client: ClientConfig): ClientStatusResult {
    return computeClientStatus(client, this.licenses, this.billings);
  }

  /** The client's most-recently-activated license, if any (a client can have several, e.g. multi-machine). */
  primaryLicenseOf(clientCode: string): License | undefined {
    const list = this.licenses.filter(l => l.clientCode === clientCode);
    return list.length
      ? [...list].sort((a, b) => new Date(b.activatedAt).getTime() - new Date(a.activatedAt).getTime())[0]
      : undefined;
  }

  get filteredClients(): ClientConfig[] {
    let list = this.clients;
    if (this.searchQuery.trim()) {
      const q = this.searchQuery.toLowerCase().trim();
      list = list.filter(c =>
        c.clientCode.toLowerCase().includes(q) ||
        c.appName.toLowerCase().includes(q) ||
        (c.tagline ?? '').toLowerCase().includes(q) ||
        (c.pointOfContact ?? '').toLowerCase().includes(q) ||
        (c.phone ?? '').toLowerCase().includes(q)
      );
    }
    if (this.statusFilter !== 'all') {
      list = list.filter(c => this.statusOf(c).status === this.statusFilter);
    }
    if (this.licenseFilter !== 'all') {
      list = list.filter(c => {
        const lic = this.primaryLicenseOf(c.clientCode);
        if (this.licenseFilter === 'none') return !lic;
        return lic?.status === this.licenseFilter.toUpperCase();
      });
    }
    if (this.expirationFilter !== 'all') {
      list = list.filter(c => {
        const lic = this.primaryLicenseOf(c.clientCode);
        if (!lic) return false;
        if (this.expirationFilter === 'never') return !lic.expiresAt;
        if (this.expirationFilter === 'expiring') {
          if (!lic.expiresAt) return false;
          const days = (new Date(lic.expiresAt).getTime() - Date.now()) / 86400000;
          return days >= 0 && days <= 30;
        }
        if (this.expirationFilter === 'expired') {
          if (!lic.expiresAt) return false;
          return new Date(lic.expiresAt).getTime() < Date.now();
        }
        return true;
      });
    }
    return this.sortClients(list);
  }

  /** Active clients first, then Trial, then Dummy; newest-added first within each group. */
  private sortClients(list: ClientConfig[]): ClientConfig[] {
    const rank: Record<string, number> = { ACTIVE: 0, TRIAL: 1, DUMMY: 2 };
    return [...list].sort((a, b) => {
      const rankDiff = rank[this.statusOf(a).status] - rank[this.statusOf(b).status];
      if (rankDiff !== 0) return rankDiff;
      return new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime();
    });
  }

  get licensedActiveCount(): number {
    return this.licenses.filter(l => l.status === 'ACTIVE').length;
  }

  get expiringSoonCount(): number {
    return this.licenses.filter(l => {
      if (!l.expiresAt) return false;
      const days = (new Date(l.expiresAt).getTime() - Date.now()) / 86400000;
      return days >= 0 && days <= 30;
    }).length;
  }

  get activeClientCount(): number { return this.clients.filter(c => this.statusOf(c).status === 'ACTIVE').length; }
  get trialClientCount(): number  { return this.clients.filter(c => this.statusOf(c).status === 'TRIAL').length; }
  get dummyClientCount(): number  { return this.clients.filter(c => this.statusOf(c).status === 'DUMMY').length; }

  // ── Billing / revenue stats (absorbed from the old standalone Licenses page) ──

  get totalRevenue(): number {
    return this.billings
      .filter(b => b.paymentStatus === 'PAID')
      .reduce((sum, b) => sum + (b.amount ? +b.amount : 0), 0);
  }

  get pendingPaymentsCount(): number {
    const codes = new Set(
      this.billings
        .filter(b => b.paymentStatus === 'PENDING' || b.paymentStatus === 'PARTIAL')
        .map(b => b.clientCode)
    );
    return codes.size;
  }

  /** Sum of priced add-ons currently enabled for this client, using the shared feature catalog. */
  clientAddonValue(client: ClientConfig): number {
    return addonValue(client.features ?? {});
  }

  // ── Contact helpers ──────────────────────────────────────────────────────

  whatsappUrl(phone: string): string {
    return `https://wa.me/${phone.replace(/[^0-9]/g, '')}`;
  }

  telegramUrl(phone: string): string {
    const clean = phone.replace(/[\s\-()]/g, '');
    return `https://t.me/${clean.startsWith('+') ? clean : '+' + clean}`;
  }

  // ── Badge helpers ────────────────────────────────────────────────────────

  clientStatusClass(status?: string | null): string {
    if (status === 'ACTIVE') return 'bg-success';
    if (status === 'TRIAL')  return 'bg-warning text-dark';
    return 'bg-secondary bg-opacity-50';
  }

  clientStatusLabel(status: string): string {
    if (status === 'ACTIVE') return 'Active';
    if (status === 'TRIAL') return 'Trial';
    return 'Dummy';
  }

  // Generic delete confirmation modal
  deleteConfirm: { title: string; message: string; onConfirm: () => void } | null = null;

  confirmDeleteAction(): void {
    if (!this.deleteConfirm) return;
    const action = this.deleteConfirm.onConfirm;
    this.deleteConfirm = null;
    action();
  }

  cancelDeleteAction(): void {
    this.deleteConfirm = null;
  }

  buildStatus: BuildStatus | null = null;
  showBuildModal = false;

  buildConfirmation: {
    clientCode: string;
    platform: string;
    label: string;
    electronVersion: string;
    outputExt: string;
    fileExt: string;
    versionNumber: string;
  } | null = null;

  openDropdown: string | null = null;
  dropdownPos = { top: 0, left: 0 };
  dropdownFlipped = false;

  showHistoryModal = false;
  historyClientCode = '';
  buildHistory: BuildLogEntry[] = [];

  @ViewChild('buildLogContainer') buildLogContainer?: ElementRef<HTMLPreElement>;

  private pollInterval: ReturnType<typeof setInterval> | null = null;

  preparingDevConfig: { [code: string]: boolean } = {};
  prepareDevConfigResult: { code: string; backendPath: string; frontendPath: string } | null = null;

  constructor(
    public clientService: ClientService,
    private licenseService: LicenseService,
    private billingService: BillingService,
    private appVersionService: AppVersionService
  ) {}

  ngOnInit(): void {
    this.load();
    this.loadLicenses();
    this.loadBillings();
    this.loadVersions();
  }

  loadBillings(): void {
    this.billingService.getAll().subscribe(data => this.billings = data);
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.openDropdown = null;
  }

  load(): void {
    this.clientService.getAll().subscribe(data => this.clients = data);
  }

  loadLicenses(): void {
    this.licenseService.getAll().subscribe(licenses => this.licenses = licenses);
  }

  loadVersions(): void {
    this.appVersionService.getAll().subscribe(v => this.versions = v);
  }

  delete(clientCode: string): void {
    this.deleteConfirm = {
      title: 'Delete Client',
      message: `Delete "${clientCode}" and all its data permanently?`,
      onConfirm: () => this.clientService.delete(clientCode).subscribe(() => this.load())
    };
  }

  exportJson(clientCode: string): void {
    this.clientService.exportConfig(clientCode).subscribe(config => {
      const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${clientCode}-client.config.json`;
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  prepareDevConfig(clientCode: string): void {
    this.preparingDevConfig[clientCode] = true;
    this.clientService.prepareDevConfig(clientCode).subscribe({
      next: (res) => {
        this.preparingDevConfig[clientCode] = false;
        this.prepareDevConfigResult = { code: clientCode, ...res };
      },
      error: (err) => {
        this.preparingDevConfig[clientCode] = false;
        alert(`Failed to prepare config: ${err.error?.message || err.message}`);
      }
    });
  }

  closePrepareConfigResult(): void {
    this.prepareDevConfigResult = null;
  }

  colorSwatches(client: ClientConfig): string[] {
    return [
      client.colorPrimary, client.colorSecondary, client.colorSuccess,
      client.colorDanger, client.colorWarning, client.colorInfo
    ];
  }

  hasAnyFeature(client: ClientConfig): boolean {
    return this.featureBadges(client).some(b => b.on);
  }

  featureBadges(client: ClientConfig): { label: string; icon: string; on: boolean }[] {
    const f = client.features;
    return [
      { label: 'i18n',           icon: '🌐', on: !!f?.multiLanguage },
      { label: 'Barcode',        icon: '📦', on: !!f?.barcode },
      { label: 'Reports',        icon: '📊', on: !!f?.reports },
      { label: 'Suppliers',      icon: '🚚', on: !!f?.suppliers },
      { label: 'Multi-Currency', icon: '💱', on: !!f?.multiCurrency },
      { label: 'Shifts',         icon: '🕐', on: !!f?.shifts },
      { label: 'Client Ledger',  icon: '👤', on: !!f?.clientLedger },
      { label: 'Supplier Ledger',icon: '🏭', on: !!f?.supplierLedger },
      { label: 'Frac. Qty',      icon: '⚖️', on: !!f?.fractionalQuantity },
      { label: 'Fixed Pricing',  icon: '💲', on: !!f?.multiCurrencyPricing },
      { label: 'Acct. Statement',icon: '🧾', on: !!f?.accountStatement },
      { label: 'Item Ledger',    icon: '📒', on: !!f?.itemLedger },
      { label: 'Batch Stocktake',icon: '📋', on: !!f?.batchStocktake },
      { label: 'Bulk Price',     icon: '💹', on: !!f?.bulkPriceUpdate },
      { label: 'Recipes/BOM',    icon: '☕', on: !!f?.productRecipes },
      { label: 'Demo Data',      icon: '🌱', on: !!f?.seedDemoData }
    ];
  }

  // -- Dropdown --

  toggleDropdown(clientCode: string, event: Event, btnEl: HTMLButtonElement): void {
    event.stopPropagation();
    if (this.openDropdown === clientCode) {
      this.openDropdown = null;
      return;
    }
    const rect = btnEl.getBoundingClientRect();
    const dropdownHeight = 210;
    const spaceBelow = window.innerHeight - rect.bottom;
    if (spaceBelow < dropdownHeight) {
      this.dropdownFlipped = true;
      this.dropdownPos = { top: rect.top - dropdownHeight - 2, left: rect.right - 200 };
    } else {
      this.dropdownFlipped = false;
      this.dropdownPos = { top: rect.bottom + 2, left: rect.right - 200 };
    }
    this.openDropdown = clientCode;
  }

  closeDropdown(): void {
    this.openDropdown = null;
  }

  // -- Build --

  private static readonly PLATFORM_META: Record<string, { label: string; electronVersion: string; outputExt: string; fileExt: string }> = {
    win:   { label: 'Windows (.exe)',     electronVersion: '28.x (latest)', outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
    win7:  { label: 'Windows 7/8 (.exe)', electronVersion: '22.3.27',       outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
    mac:   { label: 'macOS (.dmg)',       electronVersion: '28.x (latest)', outputExt: 'Disk image (.dmg)',     fileExt: 'dmg' },
    linux: { label: 'Linux (.AppImage)',  electronVersion: '28.x (latest)', outputExt: 'AppImage (.AppImage)',  fileExt: 'AppImage' },
  };

  requestBuild(clientCode: string, platform: string): void {
    const meta = ClientsComponent.PLATFORM_META[platform] ?? {
      label: platform, electronVersion: '28.x', outputExt: platform, fileExt: platform
    };
    const latestVersion = this.versions.length > 0 ? this.versions[0].versionNumber : '';
    this.buildConfirmation = { clientCode, platform, ...meta, versionNumber: latestVersion };
  }

  cancelBuildConfirmation(): void {
    this.buildConfirmation = null;
  }

  confirmBuild(): void {
    if (!this.buildConfirmation) return;
    const { clientCode, platform, versionNumber } = this.buildConfirmation;
    this.buildConfirmation = null;
    this.triggerBuild(clientCode, platform, versionNumber);
  }

  private triggerBuild(clientCode: string, platform: string, version: string): void {
    this.clientService.triggerBuild(clientCode, platform, version).subscribe({
      next: (status) => {
        this.buildStatus = status;
        this.showBuildModal = true;
        this.startPolling(clientCode);
      },
      error: (err) => {
        if (err.status === 409) {
          this.clientService.getBuildStatus(clientCode).subscribe(status => {
            this.buildStatus = status;
            this.showBuildModal = true;
            this.startPolling(clientCode);
          });
        } else {
          alert(`Failed to start build: ${err.error?.message || err.message}`);
        }
      }
    });
  }

  closeBuildModal(): void {
    this.showBuildModal = false;
    if (this.buildStatus?.status !== 'BUILDING') {
      this.stopPolling();
    }
  }

  // -- History --

  showHistory(clientCode: string): void {
    this.historyClientCode = clientCode;
    this.clientService.getBuildHistory(clientCode).subscribe(history => {
      this.buildHistory = history;
      this.showHistoryModal = true;
    });
  }

  closeHistoryModal(): void {
    this.showHistoryModal = false;
  }

  formatDuration(seconds?: number): string {
    if (seconds == null) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return m > 0 ? `${m}m ${s}s` : `${s}s`;
  }

  // -- Polling --

  private startPolling(clientCode: string): void {
    this.stopPolling();
    this.pollInterval = setInterval(() => {
      this.clientService.getBuildStatus(clientCode).subscribe({
        next: (status) => {
          this.buildStatus = status;
          this.scrollLogToBottom();
          if (status.status !== 'BUILDING') this.stopPolling();
        },
        error: () => this.stopPolling()
      });
    }, 2000);
  }

  private stopPolling(): void {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }
  }

  private scrollLogToBottom(): void {
    setTimeout(() => {
      const el = this.buildLogContainer?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 50);
  }
}

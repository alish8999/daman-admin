import { Component, OnInit, OnDestroy, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClientService, DevCurrent } from '../../services/client.service';
import { LicenseService, License, ReissuePreview } from '../../services/license.service';
import { TranslationService } from '../../services/translation.service';
import { BillingService } from '../../services/billing.service';
import { Billing } from '../../models/billing.model';
import { AppVersionService, AppVersion } from '../../services/app-version.service';
import { ClientConfig, ClientFeatures, BuildStatus, BuildLogEntry } from '../../models/client-config.model';
import { FEATURE_CATALOG } from '../../models/feature-catalog';
import { computeClientStatus, ClientStatusResult } from '../../models/client-status';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { DevRunButtonComponent } from '../../components/dev-run-button/dev-run-button.component';

@Component({
  selector: 'app-clients',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslatePipe, DevRunButtonComponent],
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
    .clients-table-wrap {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.08);
      overflow: hidden;
    }
    .clients-table {
      margin-bottom: 0;
    }
    .clients-table thead th {
      position: sticky;
      top: 0;
      z-index: 1;
      background: #fafafa;
      border-bottom: 2px solid #eee;
      font-size: 0.78rem;
      text-transform: uppercase;
      letter-spacing: 0.03em;
      color: #6b7280;
      font-weight: 700;
      padding: 12px 14px;
      white-space: nowrap;
    }
    .clients-table tbody td {
      padding: 10px 14px;
      border-bottom: 1px solid #f2f2f2;
      vertical-align: middle;
    }
    .clients-table tbody tr:hover {
      background: #f8f9fb;
    }
    .clients-table tbody tr.row--active {
      background: #f2fbf5;
    }
    .clients-table tbody tr.row--active:hover {
      background: #e8faf0;
    }
    .clients-table tbody tr:last-child td {
      border-bottom: none;
    }
    .color-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      display: inline-block;
      flex-shrink: 0;
      border: 1px solid rgba(0,0,0,0.12);
    }
    .btn-xs {
      padding: 0.2rem 0.45rem;
      font-size: 0.78rem;
      line-height: 1.3;
    }
    .min-w-0 {
      min-width: 0;
    }
  `]
})
export class ClientsComponent implements OnInit, OnDestroy {
  /** Sentinel clientCode for the generic (client-less) installer build. Matches the
   *  canonical key the backend tracks it under and returns in BuildStatus.clientCode,
   *  so the shared build handlers can route on it. */
  static readonly GENERIC = 'generic';

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

  /** Lifetime sum of PAID billing records for this client (same calculation as client-form's totalBillingPaid). */
  clientTotalPaid(clientCode: string): number {
    return this.billings
      .filter(b => b.clientCode === clientCode && b.paymentStatus === 'PAID')
      .reduce((sum, b) => sum + (b.amount ? +b.amount : 0), 0);
  }

  /** Date of this client's most recent PAID billing record, if any — table subtext under Total Paid. */
  clientLatestPaidDate(clientCode: string): string | null {
    const paid = this.billings
      .filter(b => b.clientCode === clientCode && b.paymentStatus === 'PAID')
      .sort((a, b) => new Date(b.paymentDate ?? b.createdAt).getTime() - new Date(a.paymentDate ?? a.createdAt).getTime());
    return paid[0]?.paymentDate ?? paid[0]?.createdAt ?? null;
  }

  /**
   * Short chip labels for paid add-ons only (FEATURE_CATALOG group 'addons' with a
   * price > 0) that are enabled for this client — the pricing here is real one-time/
   * yearly invoicing (see Billing), not a per-feature monthly fee, so unlike the old
   * card badge these chips carry no fabricated "$/mo" figure.
   */
  private static readonly PAID_FEATURE_LABELS: Partial<Record<keyof ClientFeatures, { label: string; icon: string }>> = {
    quotation:         { label: 'Quotations',     icon: '📝' },
    userManagement:    { label: 'Users',          icon: '👥' },
    shifts:            { label: 'Shifts',         icon: '🕐' },
    posTerminals:      { label: 'POS Terminals',  icon: '🖥️' },
    tableOrders:       { label: 'Table Orders',   icon: '🍽️' },
    quickPickCards:    { label: 'Quick-Pick',     icon: '🎛️' },
    accounting:        { label: 'Accounting',     icon: '📚' },
    currencyExchange:  { label: 'FX Exchange',    icon: '💱' },
    kitchenPrinter:    { label: 'Kitchen Printer',icon: '🖨️' },
    productRecipes:    { label: 'Recipes/BOM',    icon: '☕' },
    manufacturing:     { label: 'Manufacturing',  icon: '🏭' },
    consignment:       { label: 'Consignment',    icon: '🚚' },
    shareholders:      { label: 'Shareholders',   icon: '🤝' },
    deviceRepair:      { label: 'Device Repair',  icon: '🔧' },
  };

  private static readonly PAID_FEATURES_VISIBLE_LIMIT = 3;

  paidFeatureBadges(client: ClientConfig): { label: string; icon: string }[] {
    const f: Partial<ClientFeatures> = client.features ?? {};
    return FEATURE_CATALOG
      .filter(entry => entry.group === 'addons' && !!entry.price && !!f[entry.key])
      .map(entry => ClientsComponent.PAID_FEATURE_LABELS[entry.key] ?? { label: entry.key, icon: '⭐' });
  }

  /** First few paid features for the table cell — the rest collapse into a "+N" badge so a client with many add-ons doesn't blow out the row height. */
  visiblePaidFeatureBadges(client: ClientConfig): { label: string; icon: string }[] {
    return this.paidFeatureBadges(client).slice(0, ClientsComponent.PAID_FEATURES_VISIBLE_LIMIT);
  }

  hiddenPaidFeatureCount(client: ClientConfig): number {
    return Math.max(0, this.paidFeatureBadges(client).length - ClientsComponent.PAID_FEATURES_VISIBLE_LIMIT);
  }

  hiddenPaidFeatureNames(client: ClientConfig): string {
    return this.paidFeatureBadges(client)
      .slice(ClientsComponent.PAID_FEATURES_VISIBLE_LIMIT)
      .map(b => b.label)
      .join(', ');
  }

  // ── License expiry helpers ───────────────────────────────────────────────

  /** Days remaining until this client's primary license expires; null = no license or perpetual. */
  licenseDaysLeft(clientCode: string): number | null {
    const lic = this.primaryLicenseOf(clientCode);
    if (!lic?.expiresAt) return null;
    const ms = new Date(lic.expiresAt + 'T00:00:00').getTime() - new Date().setHours(0, 0, 0, 0);
    return Math.round(ms / 86400000);
  }

  licenseExpiryClass(clientCode: string): string {
    const days = this.licenseDaysLeft(clientCode);
    if (days === null) return 'text-muted';
    if (days < 0) return 'text-danger fw-semibold';
    if (days <= 30) return 'text-warning fw-semibold';
    return 'text-success';
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

  /** 'select' = pick an existing AppVersion; 'manual' = free-typed string (e.g. after +1 bump). */
  buildVersionMode: 'select' | 'manual' = 'select';
  /** Only offered when buildVersionMode is 'manual' and the typed string isn't an existing AppVersion — persists it (today's date, empty changelog) so writeChangelog() on the backend finds it and it shows up on the Versions page. */
  saveManualVersionAsNew = true;

  openDropdown: string | null = null;
  dropdownPos = { top: 0, left: 0 };
  dropdownFlipped = false;

  showHistoryModal = false;
  historyClientCode = '';
  buildHistory: BuildLogEntry[] = [];

  @ViewChild('buildLogContainer') buildLogContainer?: ElementRef<HTMLPreElement>;

  private pollInterval: ReturnType<typeof setInterval> | null = null;

  // ── Dev "Run as this client" workflow ────────────────────────────────────
  // The dialog + run logic live in <app-dev-run-button>; this component keeps
  // only devCurrent for the card badge + the global "Reset dev" shortcut.
  devCurrent: DevCurrent | null = null;

  // ── Stage-1 "re-issue all as v2" ──────────────────────────────────────────
  reissueModalOpen = false;
  reissuePreviewData: ReissuePreview | null = null;
  reissuePreviewLoading = false;
  reissueBusy = false;
  reissueError = '';

  constructor(
    public clientService: ClientService,
    private licenseService: LicenseService,
    private billingService: BillingService,
    private appVersionService: AppVersionService,
    private translationService: TranslationService
  ) {}

  ngOnInit(): void {
    this.load();
    this.loadLicenses();
    this.loadBillings();
    this.loadVersions();
    this.loadDevCurrent();
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

  loadDevCurrent(): void {
    this.clientService.devCurrent().subscribe({
      next: c => this.devCurrent = c,
      error: () => this.devCurrent = null
    });
  }

  hasActiveV2License(clientCode: string): boolean {
    return this.licenses.some(l => l.clientCode === clientCode
        && l.status === 'ACTIVE' && (l.payloadVersion ?? 1) >= 2);
  }

  resetDev(): void {
    if (!confirm(this.translationService.instant('devResetConfirm'))) return;
    this.clientService.devReset().subscribe({
      next: () => this.loadDevCurrent(),
      error: err => alert(err.error?.error || 'Reset failed.')
    });
  }

  openReissueModal(): void {
    this.reissueModalOpen = true;
    this.reissueError = '';
    this.reissuePreviewData = null;
    this.reissuePreviewLoading = true;
    this.licenseService.reissuePreview().subscribe({
      next: p => { this.reissuePreviewData = p; this.reissuePreviewLoading = false; },
      error: e => { this.reissueError = e.error?.error || 'Failed to load preview.'; this.reissuePreviewLoading = false; }
    });
  }

  closeReissueModal(): void {
    if (this.reissueBusy) return;
    this.reissueModalOpen = false;
  }

  downloadReissueBundle(): void {
    this.reissueBusy = true;
    this.reissueError = '';
    this.licenseService.reissueV2Bundle().subscribe({
      next: blob => {
        this.reissueBusy = false;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `daman-v2-licences-${new Date().toISOString().slice(0, 10)}.zip`;
        a.click();
        URL.revokeObjectURL(url);
        this.reissueModalOpen = false;
        this.loadLicenses();
      },
      error: e => { this.reissueBusy = false; this.reissueError = e.error?.error || 'Bundle download failed.'; }
    });
  }

  get reissuePreviewSkippedCodes(): string {
    return (this.reissuePreviewData?.skipped ?? []).map(r => r.clientCode).join(', ');
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

  /** The Build dropdown's "POS (Windows)" option only appears for a client licensed
   *  for multi-terminal POS — building a POS installer for a client whose backend
   *  rejects X-Daman-Terminal requests (TerminalContextFilter, daman-backend) would
   *  only ever produce a non-functional installer. */
  isPosBuildAllowed(clientCode: string | null): boolean {
    if (!clientCode) return false;
    const client = this.clients.find(c => c.clientCode === clientCode);
    return !!client?.features?.posTerminals;
  }

  // -- Build --

  private static readonly PLATFORM_META: Record<string, { label: string; electronVersion: string; outputExt: string; fileExt: string }> = {
    win:    { label: 'Windows (.exe)',            electronVersion: '28.x (latest)', outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
    winx86: { label: 'Windows x86 (32-bit, .exe)', electronVersion: '22.3.27',       outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
    win7:   { label: 'Windows 7/8 (.exe)',         electronVersion: '22.3.27',       outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
    mac:    { label: 'macOS (.dmg)',              electronVersion: '28.x (latest)', outputExt: 'Disk image (.dmg)',     fileExt: 'dmg' },
    linux:  { label: 'Linux (.AppImage)',         electronVersion: '28.x (latest)', outputExt: 'AppImage (.AppImage)',  fileExt: 'AppImage' },
    pos:    { label: 'POS (Windows)',             electronVersion: '28.x (latest)', outputExt: 'NSIS installer (.exe)', fileExt: 'exe' },
  };

  requestBuild(clientCode: string, platform: string): void {
    const meta = ClientsComponent.PLATFORM_META[platform] ?? {
      label: platform, electronVersion: '28.x', outputExt: platform, fileExt: platform
    };
    const latestVersion = this.versions.length > 0 ? this.versions[0].versionNumber : '';
    this.buildConfirmation = { clientCode, platform, ...meta, versionNumber: latestVersion };
    this.buildVersionMode = this.versions.length > 0 ? 'select' : 'manual';
    this.saveManualVersionAsNew = true;
  }

  /** Switch the version field to free-typed entry, seeded with the currently selected value. */
  useManualVersion(): void {
    this.buildVersionMode = 'manual';
  }

  useVersionList(): void {
    this.buildVersionMode = 'select';
    if (this.buildConfirmation && this.versions.length > 0) {
      this.buildConfirmation.versionNumber = this.versions[0].versionNumber;
    }
  }

  /**
   * Bumps the patch component of the current version (e.g. 1.4.2 → 1.4.3).
   * Falls back to appending ".1" for anything that isn't plain x.y.z — good
   * enough for the common case without pretending to be a full semver parser.
   */
  incrementBuildVersion(): void {
    if (!this.buildConfirmation) return;
    const current = this.buildConfirmation.versionNumber.trim();
    const match = current.match(/^(\d+)\.(\d+)\.(\d+)$/);
    const next = match
      ? `${match[1]}.${match[2]}.${Number(match[3]) + 1}`
      : (current ? `${current}.1` : '1.0.0');
    this.buildConfirmation.versionNumber = next;
    this.buildVersionMode = 'manual';
  }

  /** True when the typed/selected version isn't a known AppVersion — used to offer "save as new version". */
  get buildVersionIsNew(): boolean {
    if (!this.buildConfirmation) return false;
    const v = this.buildConfirmation.versionNumber.trim();
    return !!v && !this.versions.some(x => x.versionNumber === v);
  }

  /** Kick off the generic (client-less) installer build — routes through the same
   *  Confirm-Build + Build-Log modals as a per-client build via the GENERIC sentinel. */
  requestGenericBuild(platform: string = 'win'): void {
    this.requestBuild(ClientsComponent.GENERIC, platform);
  }

  get isGenericBuild(): boolean {
    return this.buildStatus?.clientCode === ClientsComponent.GENERIC;
  }

  cancelBuildConfirmation(): void {
    this.buildConfirmation = null;
  }

  confirmBuild(): void {
    if (!this.buildConfirmation) return;
    const { clientCode, platform, versionNumber } = this.buildConfirmation;
    const shouldSaveVersion = this.buildVersionMode === 'manual'
      && this.saveManualVersionAsNew
      && this.buildVersionIsNew;
    this.buildConfirmation = null;

    if (!shouldSaveVersion) {
      this.triggerBuild(clientCode, platform, versionNumber);
      return;
    }

    this.appVersionService.create({
      versionNumber,
      releaseDate: new Date().toISOString().slice(0, 10),
      changelogText: ''
    }).subscribe({
      next: (v) => {
        this.versions = [v, ...this.versions];
        this.triggerBuild(clientCode, platform, versionNumber);
      },
      error: () => {
        // Version-record creation is a convenience (changelog.txt lookup) — a failure
        // here (e.g. duplicate versionNumber from a race) shouldn't block the build.
        this.triggerBuild(clientCode, platform, versionNumber);
      }
    });
  }

  private triggerBuild(clientCode: string, platform: string, version: string): void {
    const generic = clientCode === ClientsComponent.GENERIC;
    const start$ = generic
      ? this.clientService.triggerGenericBuild(platform, version)
      : this.clientService.triggerBuild(clientCode, platform, version);
    start$.subscribe({
      next: (status) => {
        this.buildStatus = status;
        this.showBuildModal = true;
        this.startPolling(clientCode);
      },
      error: (err) => {
        if (err.status === 409) {
          const status$ = generic
            ? this.clientService.getGenericBuildStatus()
            : this.clientService.getBuildStatus(clientCode);
          status$.subscribe(status => {
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
    const history$ = clientCode === ClientsComponent.GENERIC
      ? this.clientService.getGenericBuildHistory()
      : this.clientService.getBuildHistory(clientCode);
    history$.subscribe(history => {
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
    const generic = clientCode === ClientsComponent.GENERIC;
    this.pollInterval = setInterval(() => {
      const status$ = generic
        ? this.clientService.getGenericBuildStatus()
        : this.clientService.getBuildStatus(clientCode);
      status$.subscribe({
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

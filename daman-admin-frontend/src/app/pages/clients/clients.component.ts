import { Component, OnInit, OnDestroy, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClientService } from '../../services/client.service';
import { LicenseService, License } from '../../services/license.service';
import { AppVersionService, AppVersion } from '../../services/app-version.service';
import { ClientConfig, BuildStatus, BuildLogEntry } from '../../models/client-config.model';

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
    }
    .feature-badge {
      font-size: 0.72rem;
      padding: 0.25em 0.55em;
      letter-spacing: 0.01em;
    }
    .page-scroll-container {
      height: calc(100vh - 56px);
      overflow-y: auto;
    }
  `]
})
export class ClientsComponent implements OnInit, OnDestroy {
  clients: ClientConfig[] = [];
  licenseMap: Record<string, License> = {};
  versions: AppVersion[] = [];

  buildStatus: BuildStatus | null = null;
  showBuildModal = false;

  buildConfirmation: {
    clientCode: string;
    platform: string;
    label: string;
    electronVersion: string;
    outputExt: string;
    versionNumber: string;
  } | null = null;

  openDropdown: string | null = null;
  dropdownPos = { top: 0, left: 0 };

  showHistoryModal = false;
  historyClientCode = '';
  buildHistory: BuildLogEntry[] = [];

  // License revoke confirmation
  revokeConfirm: { clientCode: string } | null = null;

  // License renew confirmation
  renewConfirm: { clientCode: string; expiresAt: string } | null = null;

  @ViewChild('buildLogContainer') buildLogContainer?: ElementRef<HTMLPreElement>;

  private pollInterval: ReturnType<typeof setInterval> | null = null;

  preparingDevConfig: { [code: string]: boolean } = {};
  prepareDevConfigResult: { code: string; backendPath: string; frontendPath: string } | null = null;

  constructor(
    public clientService: ClientService,
    private licenseService: LicenseService,
    private appVersionService: AppVersionService
  ) {}

  ngOnInit(): void {
    this.load();
    this.loadLicenses();
    this.loadVersions();
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
    this.licenseService.getAll().subscribe(licenses => {
      this.licenseMap = {};
      licenses.forEach(l => this.licenseMap[l.clientCode] = l);
    });
  }

  loadVersions(): void {
    this.appVersionService.getAll().subscribe(v => this.versions = v);
  }

  delete(clientCode: string): void {
    if (!confirm(`Delete client "${clientCode}"? This cannot be undone.`)) return;
    this.clientService.delete(clientCode).subscribe(() => this.load());
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
      { label: 'Demo Data',      icon: '🌱', on: !!f?.seedDemoData }
    ];
  }

  // -- License helpers --

  licenseOf(clientCode: string): License | undefined {
    return this.licenseMap[clientCode];
  }

  openRevokeConfirm(clientCode: string): void {
    this.revokeConfirm = { clientCode };
  }

  cancelRevoke(): void {
    this.revokeConfirm = null;
  }

  confirmRevoke(): void {
    if (!this.revokeConfirm) return;
    const { clientCode } = this.revokeConfirm;
    this.revokeConfirm = null;
    this.licenseService.revokeByClient(clientCode).subscribe({
      next: () => this.loadLicenses(),
      error: (err) => alert(`Revoke failed: ${err.error?.message || err.message}`)
    });
  }

  openRenewConfirm(clientCode: string): void {
    this.renewConfirm = { clientCode, expiresAt: '' };
  }

  cancelRenew(): void {
    this.renewConfirm = null;
  }

  confirmRenew(): void {
    if (!this.renewConfirm) return;
    const { clientCode, expiresAt } = this.renewConfirm;
    this.renewConfirm = null;
    this.licenseService.renewByClient(clientCode, expiresAt || undefined).subscribe({
      next: () => this.loadLicenses(),
      error: (err) => alert(`Renew failed: ${err.error?.message || err.message}`)
    });
  }

  // -- Dropdown --

  toggleDropdown(clientCode: string, event: Event, btnEl: HTMLButtonElement): void {
    event.stopPropagation();
    if (this.openDropdown === clientCode) {
      this.openDropdown = null;
      return;
    }
    const rect = btnEl.getBoundingClientRect();
    this.dropdownPos = { top: rect.bottom + 2, left: rect.right - 180 };
    this.openDropdown = clientCode;
  }

  closeDropdown(): void {
    this.openDropdown = null;
  }

  // -- Build --

  private static readonly PLATFORM_META: Record<string, { label: string; electronVersion: string; outputExt: string }> = {
    win:   { label: 'Windows (.exe)',     electronVersion: '28.x (latest)', outputExt: 'NSIS installer (.exe)' },
    win7:  { label: 'Windows 7/8 (.exe)', electronVersion: '22.3.27',       outputExt: 'NSIS installer (.exe)' },
    mac:   { label: 'macOS (.dmg)',       electronVersion: '28.x (latest)', outputExt: 'Disk image (.dmg)'    },
    linux: { label: 'Linux (.AppImage)',  electronVersion: '28.x (latest)', outputExt: 'AppImage (.AppImage)' },
  };

  requestBuild(clientCode: string, platform: string): void {
    const meta = ClientsComponent.PLATFORM_META[platform] ?? {
      label: platform, electronVersion: '28.x', outputExt: platform
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

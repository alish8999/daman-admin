import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LicenseService, License, GenerateLicenseResponse } from '../../services/license.service';
import { ClientService } from '../../services/client.service';
import { BillingService } from '../../services/billing.service';
import { Billing } from '../../models/billing.model';
import { ClientConfig } from '../../models/client-config.model';

export interface MergedRow {
  client: ClientConfig;
  license: License | null;
  billing: Billing | null;
  isExpired: boolean;
  isExpiringSoon: boolean;
  supportDaysLeft: number | null;
}

@Component({
  selector: 'app-licenses',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './licenses.component.html',
  styles: [`
    .page-scroll-container {
      height: calc(100vh - 56px);
      overflow-y: auto;
    }
    .page-hero {
      background: linear-gradient(135deg, #1e1e2e 0%, #2d2d44 100%);
      border-radius: 12px;
      padding: 20px 24px;
      margin-bottom: 1.25rem;
      color: #fff;
    }
    .stat-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background: rgba(255,255,255,0.15);
      border-radius: 20px;
      padding: 2px 10px;
      font-size: 0.78rem;
      backdrop-filter: blur(4px);
    }
    .stats-card {
      background: #fff;
      border-radius: 12px;
      padding: 16px 20px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.06);
    }
    .stats-card .label { font-size: 0.72rem; text-transform: uppercase; letter-spacing: .05em; color: #8a8aa0; font-weight: 600; }
    .stats-card .value { font-size: 1.6rem; font-weight: 700; line-height: 1.1; }
    .filter-bar {
      background: #fff;
      border-radius: 10px;
      padding: 12px 16px;
      box-shadow: 0 1px 4px rgba(0,0,0,0.07);
    }
    .search-input-wrap {
      position: relative;
    }
    .search-input-wrap .search-icon {
      position: absolute;
      left: 10px;
      top: 50%;
      transform: translateY(-50%);
      color: #aaa;
      pointer-events: none;
    }
    .search-input-wrap input {
      padding-left: 32px;
    }
    .sales-table-card {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.06);
      overflow: hidden;
    }
    .sales-table-card .table { margin-bottom: 0; }
    .sales-table-card thead th {
      background: #f6f7fb;
      border-bottom: 1px solid #e9ecef;
      color: #4a4a5e;
      font-weight: 600;
      font-size: 0.78rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      padding-top: 12px;
      padding-bottom: 12px;
    }
    .sales-table-card tbody tr { transition: background 0.15s ease; }
    .sales-table-card tbody tr:hover { background: #fafbff; }
    .sales-table-card tbody td { padding-top: 14px; padding-bottom: 14px; vertical-align: middle; }
    .client-name { font-weight: 600; color: #1e1e2e; }
    .client-code { font-family: 'Cascadia Code','Fira Code',monospace; font-size: 0.72rem; color: #8a8aa0; }
    .license-key-display {
      font-family: 'Cascadia Code','Fira Code',monospace; font-size: 0.75rem; word-break: break-all;
      background: #1e1e2e; color: #d6d6e6; padding: 14px; border-radius: 8px; border: 1px solid #2d2d44;
      max-height: 220px; overflow-y: auto;
    }
    .empty-state {
      background: #fff; border-radius: 12px; padding: 60px 24px;
      text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.05); color: #6b7280;
    }
    .empty-state .emoji { font-size: 2.5rem; display: block; margin-bottom: 12px; opacity: 0.6; }
    .btn-xs { padding: 0.18rem 0.55rem; font-size: 0.72rem; border-radius: 6px; }
    .copy-btn.copied { background: #15803d; color: #fff; border-color: #15803d; }
    .modal-content { border-radius: 12px; border: none; box-shadow: 0 18px 50px rgba(15,15,30,0.25); }
    .gradient-btn { background: linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%); border: none; color: #fff; font-weight: 600; }
    .gradient-btn:hover { color: #fff; opacity: 0.92; }
  `]
})
export class LicensesComponent implements OnInit {
  licenses: License[] = [];
  clients: ClientConfig[] = [];
  billings: Billing[] = [];
  searchQuery = '';
  packageFilter = '';
  paymentFilter = '';
  licenseFilter = '';

  showGenerateModal = false;
  generateForm = { machineId: '', clientCode: '', label: '', expiresAt: '' };
  generateResult: GenerateLicenseResponse | null = null;
  generateError = '';
  generating = false;
  copied = false;

  renewModal: { license: License; expiresAt: string } | null = null;
  renewing = false;
  renewError = '';

  deleteConfirm: { title: string; message: string; onConfirm: () => void } | null = null;

  revokeConfirm: { license: License } | null = null;
  revoking = false;
  revokeError = '';

  constructor(
    private licenseService: LicenseService,
    private clientService: ClientService,
    private billingService: BillingService
  ) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.licenseService.getAll().subscribe(data => this.licenses = data);
    this.clientService.getAll().subscribe(c => this.clients = c);
    this.billingService.getAll().subscribe(b => this.billings = b);
  }

  // ─── Merged rows ─────────────────────────────────────────────────────────

  get mergedRows(): MergedRow[] {
    return this.clients.map(client => {
      const clientLicenses = this.licenses
        .filter(l => l.clientCode === client.clientCode)
        .sort((a, b) => new Date(b.activatedAt).getTime() - new Date(a.activatedAt).getTime());
      const activeLicense = clientLicenses.find(l => l.status === 'ACTIVE') ?? clientLicenses[0] ?? null;
      const expired = activeLicense ? this.isExpired(activeLicense) : false;
      const expiringSoon = activeLicense ? this.isExpiringSoon(activeLicense) : false;

      const clientBillings = this.billings
        .filter(b => b.clientCode === client.clientCode)
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      const latestBilling = clientBillings[0] ?? null;

      return {
        client,
        license: activeLicense,
        billing: latestBilling,
        isExpired: expired,
        isExpiringSoon: expiringSoon,
        supportDaysLeft: this.calcSupportDaysLeft(latestBilling)
      };
    });
  }

  get filteredRows(): MergedRow[] {
    const q = this.searchQuery.trim().toLowerCase();
    return this.mergedRows.filter(row => {
      if (this.packageFilter && row.client.packageTier !== this.packageFilter) return false;
      if (this.paymentFilter && row.billing?.paymentStatus !== this.paymentFilter) return false;
      if (this.licenseFilter) {
        if (this.licenseFilter === 'ACTIVE' && (row.isExpired || !row.license || row.license.status !== 'ACTIVE')) return false;
        if (this.licenseFilter === 'EXPIRED' && !row.isExpired) return false;
        if (this.licenseFilter === 'NONE' && row.license !== null) return false;
        if (this.licenseFilter === 'REVOKED' && row.license?.status !== 'REVOKED') return false;
      }
      if (q) {
        const haystack = [
          row.client.appName,
          row.client.clientCode,
          row.client.pointOfContact ?? '',
          row.client.phone ?? '',
          row.client.email ?? ''
        ].join(' ').toLowerCase();
        return haystack.includes(q);
      }
      return true;
    });
  }

  // ─── Stats ───────────────────────────────────────────────────────────────

  get totalRevenue(): number {
    return this.billings
      .filter(b => b.paymentStatus === 'PAID')
      .reduce((sum, b) => sum + (b.amount ? +b.amount : 0), 0);
  }

  get paidCount(): number {
    return this.mergedRows.filter(r => r.billing?.paymentStatus === 'PAID').length;
  }

  get pendingCount(): number {
    return this.mergedRows.filter(r =>
      r.billing?.paymentStatus === 'PENDING' || r.billing?.paymentStatus === 'PARTIAL'
    ).length;
  }

  get activeLicenseCount(): number {
    return this.mergedRows.filter(r => r.license?.status === 'ACTIVE' && !r.isExpired).length;
  }

  get expiringSoonCount(): number {
    return this.mergedRows.filter(r => r.isExpiringSoon && !r.isExpired).length;
  }

  // ─── Badge helpers ───────────────────────────────────────────────────────

  packageBadgeClass(tier?: string | null): string {
    if (tier === 'ULTIMATE') return 'bg-dark';
    if (tier === 'PRO') return 'bg-primary';
    if (tier === 'BASIC') return 'bg-secondary';
    return 'bg-light text-muted';
  }

  clientStatusBadgeClass(status?: string | null): string {
    if (status === 'ACTIVE') return 'bg-success';
    if (status === 'INACTIVE') return 'bg-danger';
    if (status === 'TRIAL') return 'bg-warning text-dark';
    return 'bg-light text-muted';
  }

  paymentBadgeClass(status?: string | null): string {
    if (status === 'PAID') return 'bg-success';
    if (status === 'PENDING') return 'bg-warning text-dark';
    if (status === 'PARTIAL') return 'bg-info text-dark';
    return 'bg-light text-muted border';
  }

  licenseBadgeClass(row: MergedRow): string {
    if (!row.license) return 'bg-light text-muted';
    if (row.license.status === 'REVOKED') return 'bg-danger';
    if (row.isExpired) return 'bg-secondary';
    if (row.isExpiringSoon) return 'bg-warning text-dark';
    return 'bg-success';
  }

  licenseLabel(row: MergedRow): string {
    if (!row.license) return 'None';
    if (row.license.status === 'REVOKED') return 'Revoked';
    if (row.isExpired) return 'Expired';
    if (row.isExpiringSoon) {
      const d = this.daysUntilExpiry(row.license);
      return d !== null ? `Expiring (${d}d)` : 'Expiring';
    }
    if (!row.license.expiresAt) return 'Active ∞';
    return 'Active';
  }

  // ─── Expiry helpers ──────────────────────────────────────────────────────

  isExpiringSoon(l: License): boolean {
    const days = this.daysUntilExpiry(l);
    return days !== null && days >= 0 && days <= 30;
  }

  isExpired(l: License): boolean {
    const days = this.daysUntilExpiry(l);
    return days !== null && days < 0;
  }

  daysUntilExpiry(l: License): number | null {
    if (!l.expiresAt) return null;
    const expiry = new Date(l.expiresAt + 'T00:00:00');
    const today = new Date(); today.setHours(0,0,0,0);
    return Math.round((expiry.getTime() - today.getTime()) / 86400000);
  }

  calcSupportDaysLeft(billing: Billing | null): number | null {
    if (!billing?.supportEndDate) return null;
    const end = new Date(billing.supportEndDate + 'T00:00:00');
    const today = new Date(); today.setHours(0,0,0,0);
    return Math.round((end.getTime() - today.getTime()) / 86400000);
  }

  // ─── Generate ────────────────────────────────────────────────────────────

  openGenerateModal(clientCode?: string): void {
    this.generateForm = { machineId: '', clientCode: clientCode ?? '', label: '', expiresAt: '' };
    this.generateResult = null;
    this.generateError = '';
    this.showGenerateModal = true;
  }

  closeGenerateModal(): void {
    this.showGenerateModal = false;
    if (this.generateResult) this.loadAll();
  }

  generate(): void {
    if (!this.generateForm.machineId.trim() || !this.generateForm.clientCode) {
      this.generateError = 'Machine ID and Client are required';
      return;
    }
    this.generating = true;
    this.generateError = '';
    this.licenseService.generate({
      machineId: this.generateForm.machineId.trim(),
      clientCode: this.generateForm.clientCode,
      label: this.generateForm.label.trim() || undefined,
      expiresAt: this.generateForm.expiresAt || undefined
    }).subscribe({
      next: (result) => { this.generating = false; this.generateResult = result; },
      error: (err) => { this.generating = false; this.generateError = err.error?.error || 'Failed to generate license'; }
    });
  }

  copyLicenseKey(key: string): void {
    navigator.clipboard.writeText(key).then(() => {
      this.copied = true;
      setTimeout(() => this.copied = false, 2000);
    });
  }

  // ─── Revoke ──────────────────────────────────────────────────────────────

  revoke(license: License): void {
    this.revokeError = '';
    this.revokeConfirm = { license };
  }

  cancelRevokeConfirm(): void {
    this.revokeConfirm = null;
    this.revokeError = '';
  }

  confirmRevokeAction(): void {
    if (!this.revokeConfirm || this.revoking) return;
    const license = this.revokeConfirm.license;
    this.revoking = true;
    this.licenseService.revoke(license.id).subscribe({
      next: () => { this.revoking = false; this.revokeConfirm = null; this.loadAll(); },
      error: (err) => { this.revoking = false; this.revokeError = err.error?.message || 'Failed to revoke.'; }
    });
  }

  // ─── Delete ──────────────────────────────────────────────────────────────

  deleteLicense(license: License): void {
    this.deleteConfirm = {
      title: 'Delete License',
      message: `Permanently delete the license record for "${license.clientName || license.clientCode}"?`,
      onConfirm: () => this.licenseService.delete(license.id).subscribe(() => this.loadAll())
    };
  }

  confirmDeleteAction(): void {
    if (!this.deleteConfirm) return;
    const action = this.deleteConfirm.onConfirm;
    this.deleteConfirm = null;
    action();
  }

  cancelDeleteAction(): void { this.deleteConfirm = null; }

  // ─── Renew ───────────────────────────────────────────────────────────────

  openRenewModal(license: License): void {
    this.renewModal = { license, expiresAt: '' };
    this.renewError = '';
  }

  closeRenewModal(): void { this.renewModal = null; }

  confirmRenew(): void {
    if (!this.renewModal) return;
    this.renewing = true;
    this.renewError = '';
    const { license, expiresAt } = this.renewModal;
    this.licenseService.renewById(license.id, expiresAt || undefined).subscribe({
      next: () => { this.renewing = false; this.renewModal = null; this.loadAll(); },
      error: (err) => { this.renewing = false; this.renewError = err.error?.message || 'Renew failed.'; }
    });
  }

  // ─── Misc ────────────────────────────────────────────────────────────────

  truncateMachineId(id: string): string {
    return id.length > 20 ? id.substring(0, 20) + '…' : id;
  }

  clearFilters(): void {
    this.searchQuery = '';
    this.packageFilter = '';
    this.paymentFilter = '';
    this.licenseFilter = '';
  }

  trackByCode(_i: number, row: MergedRow): string { return row.client.clientCode; }
}

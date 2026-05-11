import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LicenseService, License, GenerateLicenseResponse } from '../../services/license.service';
import { ClientService } from '../../services/client.service';
import { ClientConfig } from '../../models/client-config.model';

@Component({
  selector: 'app-licenses',
  standalone: true,
  imports: [CommonModule, FormsModule],
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
    .license-table-card {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.06);
      overflow: hidden;
    }
    .license-table-card .table {
      margin-bottom: 0;
    }
    .license-table-card thead th {
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
    .license-table-card tbody tr {
      transition: background 0.15s ease;
    }
    .license-table-card tbody tr:hover {
      background: #fafbff;
    }
    .license-table-card tbody td {
      padding-top: 14px;
      padding-bottom: 14px;
      vertical-align: middle;
    }
    .license-key-display {
      font-family: 'Cascadia Code', 'Fira Code', monospace;
      font-size: 0.75rem;
      word-break: break-all;
      background: #1e1e2e;
      color: #d6d6e6;
      padding: 14px;
      border-radius: 8px;
      border: 1px solid #2d2d44;
      max-height: 220px;
      overflow-y: auto;
    }
    .machine-id-cell {
      font-family: 'Cascadia Code', 'Fira Code', monospace;
      font-size: 0.75rem;
      color: #5a5a72;
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .client-cell .client-name {
      font-weight: 600;
      color: #1e1e2e;
    }
    .client-cell .client-code {
      font-family: 'Cascadia Code', 'Fira Code', monospace;
      font-size: 0.72rem;
      color: #8a8aa0;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-weight: 600;
      font-size: 0.72rem;
      padding: 4px 10px;
      border-radius: 999px;
      letter-spacing: 0.03em;
    }
    .status-pill::before {
      content: '';
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
    }
    .status-pill.active {
      background: rgba(34,197,94,0.12);
      color: #15803d;
    }
    .status-pill.revoked {
      background: rgba(239,68,68,0.12);
      color: #b91c1c;
    }
    .status-pill.expired {
      background: rgba(107,114,128,0.18);
      color: #4b5563;
    }
    .expiry-cell .never {
      color: #6b7280;
      font-style: italic;
    }
    .expiry-cell .soon {
      color: #b45309;
      font-weight: 600;
    }
    .expiry-cell .past {
      color: #b91c1c;
      font-weight: 600;
      text-decoration: line-through;
    }
    .empty-state {
      background: #fff;
      border-radius: 12px;
      padding: 60px 24px;
      text-align: center;
      box-shadow: 0 2px 8px rgba(0,0,0,0.05);
      color: #6b7280;
    }
    .empty-state .emoji {
      font-size: 2.5rem;
      display: block;
      margin-bottom: 12px;
      opacity: 0.6;
    }
    .btn-xs {
      padding: 0.18rem 0.55rem;
      font-size: 0.72rem;
      border-radius: 6px;
    }
    .copy-btn.copied {
      background: #15803d;
      color: #fff;
      border-color: #15803d;
    }
    .modal-content {
      border-radius: 12px;
      border: none;
      box-shadow: 0 18px 50px rgba(15, 15, 30, 0.25);
    }
    .gradient-btn {
      background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
      border: none;
      color: #fff;
      font-weight: 600;
    }
    .gradient-btn:hover { color: #fff; opacity: 0.92; }
  `]
})
export class LicensesComponent implements OnInit {
  licenses: License[] = [];
  clients: ClientConfig[] = [];
  filter: 'all' | 'active' | 'revoked' = 'all';
  searchQuery = '';

  showGenerateModal = false;
  generateForm = { machineId: '', clientCode: '', expiresAt: '' };
  generateResult: GenerateLicenseResponse | null = null;
  generateError = '';
  generating = false;
  copied = false;

  renewModal: { license: License; expiresAt: string } | null = null;
  renewing = false;
  renewError = '';

  /** Generic delete-confirmation dialog state. */
  deleteConfirm: { title: string; message: string; onConfirm: () => void } | null = null;

  /** Revoke-confirmation dialog state. */
  revokeConfirm: { license: License } | null = null;
  revoking = false;
  revokeError = '';

  constructor(
    private licenseService: LicenseService,
    private clientService: ClientService
  ) {}

  ngOnInit(): void {
    this.loadLicenses();
    this.clientService.getAll().subscribe(c => this.clients = c);
  }

  loadLicenses(): void {
    this.licenseService.getAll().subscribe(data => this.licenses = data);
  }

  // ─── Filtering & stats ───────────────────────────────────────────────────

  get filteredLicenses(): License[] {
    const q = this.searchQuery.trim().toLowerCase();
    let list = this.licenses;
    if (this.filter === 'active') list = list.filter(l => l.status === 'ACTIVE');
    else if (this.filter === 'revoked') list = list.filter(l => l.status === 'REVOKED');
    if (q) {
      list = list.filter(l =>
        (l.clientName || '').toLowerCase().includes(q) ||
        (l.clientCode || '').toLowerCase().includes(q) ||
        (l.machineId || '').toLowerCase().includes(q)
      );
    }
    return list;
  }

  get activeCount(): number {
    return this.licenses.filter(l => l.status === 'ACTIVE').length;
  }

  get revokedCount(): number {
    return this.licenses.filter(l => l.status === 'REVOKED').length;
  }

  /** Active licenses expiring within the next 30 days (and not already past). */
  get expiringSoonCount(): number {
    return this.licenses.filter(l => l.status === 'ACTIVE' && this.isExpiringSoon(l)).length;
  }

  // ─── Expiry helpers ──────────────────────────────────────────────────────

  isExpiringSoon(l: License): boolean {
    if (!l.expiresAt) return false;
    const days = this.daysUntilExpiry(l);
    return days !== null && days >= 0 && days <= 30;
  }

  isExpired(l: License): boolean {
    if (!l.expiresAt) return false;
    const days = this.daysUntilExpiry(l);
    return days !== null && days < 0;
  }

  /** Days from today until expiry; null when there's no expiry date. */
  daysUntilExpiry(l: License): number | null {
    if (!l.expiresAt) return null;
    const expiry = new Date(l.expiresAt + 'T00:00:00');
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diffMs = expiry.getTime() - today.getTime();
    return Math.round(diffMs / (1000 * 60 * 60 * 24));
  }

  // ─── Generate ────────────────────────────────────────────────────────────

  openGenerateModal(): void {
    this.generateForm = { machineId: '', clientCode: '', expiresAt: '' };
    this.generateResult = null;
    this.generateError = '';
    this.showGenerateModal = true;
  }

  closeGenerateModal(): void {
    this.showGenerateModal = false;
    if (this.generateResult) {
      this.loadLicenses();
    }
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
      expiresAt: this.generateForm.expiresAt || undefined
    }).subscribe({
      next: (result) => {
        this.generating = false;
        this.generateResult = result;
      },
      error: (err) => {
        this.generating = false;
        this.generateError = err.error?.error || 'Failed to generate license';
      }
    });
  }

  copyLicenseKey(key: string): void {
    navigator.clipboard.writeText(key).then(() => {
      this.copied = true;
      setTimeout(() => this.copied = false, 2000);
    });
  }

  // ─── Revoke (with confirmation) ──────────────────────────────────────────

  revoke(license: License): void {
    this.openRevokeConfirm(license);
  }

  openRevokeConfirm(license: License): void {
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
    this.revokeError = '';
    this.licenseService.revoke(license.id).subscribe({
      next: () => {
        this.revoking = false;
        this.revokeConfirm = null;
        this.loadLicenses();
      },
      error: (err) => {
        this.revoking = false;
        this.revokeError = err.error?.message || err.error?.error || 'Failed to revoke license.';
      }
    });
  }

  // ─── Delete (with confirmation) ──────────────────────────────────────────

  deleteLicense(license: License): void {
    this.deleteConfirm = {
      title: 'Delete License',
      message: `Permanently delete the license record for "${license.clientName || license.clientCode}"? This cannot be undone.`,
      onConfirm: () => this.licenseService.delete(license.id).subscribe(() => this.loadLicenses())
    };
  }

  confirmDeleteAction(): void {
    if (!this.deleteConfirm) return;
    const action = this.deleteConfirm.onConfirm;
    this.deleteConfirm = null;
    action();
  }

  cancelDeleteAction(): void {
    this.deleteConfirm = null;
  }

  // ─── Renew ───────────────────────────────────────────────────────────────

  truncateMachineId(id: string): string {
    return id.length > 20 ? id.substring(0, 20) + '…' : id;
  }

  openRenewModal(license: License): void {
    this.renewModal = { license, expiresAt: '' };
    this.renewError = '';
  }

  closeRenewModal(): void {
    this.renewModal = null;
  }

  confirmRenew(): void {
    if (!this.renewModal) return;
    this.renewing = true;
    this.renewError = '';
    const { license, expiresAt } = this.renewModal;
    this.licenseService.renewByClient(license.clientCode, expiresAt || undefined).subscribe({
      next: () => {
        this.renewing = false;
        this.renewModal = null;
        this.loadLicenses();
      },
      error: (err) => {
        this.renewing = false;
        this.renewError = err.error?.message || err.error?.error || 'Renew failed.';
      }
    });
  }

  // ─── Misc ────────────────────────────────────────────────────────────────

  clearFilters(): void {
    this.searchQuery = '';
    this.filter = 'all';
  }

  trackById(_idx: number, l: License): number {
    return l.id;
  }
}

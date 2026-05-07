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
    .license-key-display {
      font-family: 'Cascadia Code', 'Fira Code', monospace;
      font-size: 0.75rem;
      word-break: break-all;
      background: #f8f9fa;
      padding: 12px;
      border-radius: 6px;
      border: 1px solid #dee2e6;
    }
    .machine-id-cell {
      font-family: 'Cascadia Code', 'Fira Code', monospace;
      font-size: 0.75rem;
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .filter-tabs .btn { border-radius: 20px; font-size: 0.85rem; }
    .filter-tabs .btn.active { font-weight: 600; }
  `]
})
export class LicensesComponent implements OnInit {
  licenses: License[] = [];
  clients: ClientConfig[] = [];
  filter: 'all' | 'active' | 'revoked' = 'all';

  showGenerateModal = false;
  generateForm = { machineId: '', clientCode: '', expiresAt: '' };
  generateResult: GenerateLicenseResponse | null = null;
  generateError = '';
  generating = false;
  copied = false;

  renewModal: { license: License; expiresAt: string } | null = null;
  renewing = false;
  renewError = '';

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

  get filteredLicenses(): License[] {
    if (this.filter === 'active') return this.licenses.filter(l => l.status === 'ACTIVE');
    if (this.filter === 'revoked') return this.licenses.filter(l => l.status === 'REVOKED');
    return this.licenses;
  }

  get activeCount(): number {
    return this.licenses.filter(l => l.status === 'ACTIVE').length;
  }

  get revokedCount(): number {
    return this.licenses.filter(l => l.status === 'REVOKED').length;
  }

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

  revoke(license: License): void {
    if (!confirm(`Revoke license for "${license.clientName}" on machine ${license.machineId.substring(0, 12)}...?`)) return;
    this.licenseService.revoke(license.id).subscribe(() => this.loadLicenses());
  }

  truncateMachineId(id: string): string {
    return id.length > 16 ? id.substring(0, 16) + '...' : id;
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
}

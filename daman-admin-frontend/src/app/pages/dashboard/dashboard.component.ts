import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ClientService } from '../../services/client.service';
import { LicenseService, License } from '../../services/license.service';
import { BillingService } from '../../services/billing.service';
import { Billing } from '../../models/billing.model';
import { ClientConfig } from '../../models/client-config.model';
import { computeClientStatus } from '../../models/client-status';

interface MonthBar {
  label: string;
  amount: number;
  heightPct: number;
}

interface ExpiringItem {
  clientCode: string;
  appName: string;
  expiresAt: string;
  daysLeft: number;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html'
})
export class DashboardComponent implements OnInit {
  clients: ClientConfig[] = [];
  licenses: License[] = [];
  billings: Billing[] = [];
  loading = true;

  constructor(
    private clientService: ClientService,
    private licenseService: LicenseService,
    private billingService: BillingService
  ) {}

  ngOnInit(): void {
    forkJoin({
      clients: this.clientService.getAll(),
      licenses: this.licenseService.getAll(),
      billings: this.billingService.getAll()
    }).subscribe(({ clients, licenses, billings }) => {
      this.clients = clients;
      this.licenses = licenses;
      this.billings = billings;
      this.loading = false;
    });
  }

  // ── Stats ────────────────────────────────────────────────────────────────

  get totalRevenue(): number {
    return this.billings.filter(b => b.paymentStatus === 'PAID').reduce((s, b) => s + (b.amount ? +b.amount : 0), 0);
  }

  get pendingPaymentsCount(): number {
    return new Set(
      this.billings.filter(b => b.paymentStatus === 'PENDING' || b.paymentStatus === 'PARTIAL').map(b => b.clientCode)
    ).size;
  }

  get activeCount(): number {
    return this.clients.filter(c => this.statusOf(c) === 'ACTIVE').length;
  }

  get trialCount(): number {
    return this.clients.filter(c => this.statusOf(c) === 'TRIAL').length;
  }

  get dummyCount(): number {
    return this.clients.filter(c => this.statusOf(c) === 'DUMMY').length;
  }

  get activeLicenseCount(): number {
    return this.licenses.filter(l => l.status === 'ACTIVE').length;
  }

  get expiringSoonCount(): number {
    return this.expiringLicenses.length;
  }

  statusOf(client: ClientConfig): 'ACTIVE' | 'TRIAL' | 'DUMMY' {
    return computeClientStatus(client, this.licenses, this.billings).status;
  }

  // ── Revenue by month (last 6 months) ────────────────────────────────────

  get revenueByMonth(): MonthBar[] {
    const now = new Date();
    const buckets: { key: string; label: string; amount: number }[] = [];
    for (let i = 5; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      buckets.push({
        key: `${d.getFullYear()}-${d.getMonth()}`,
        label: d.toLocaleString('en', { month: 'short' }),
        amount: 0
      });
    }
    const byKey = new Map(buckets.map(b => [b.key, b]));
    for (const b of this.billings) {
      if (b.paymentStatus !== 'PAID') continue;
      const dateStr = b.paymentDate ?? b.createdAt;
      if (!dateStr) continue;
      const d = new Date(dateStr);
      const key = `${d.getFullYear()}-${d.getMonth()}`;
      const bucket = byKey.get(key);
      if (bucket) bucket.amount += b.amount ? +b.amount : 0;
    }
    const max = Math.max(1, ...buckets.map(b => b.amount));
    return buckets.map(b => ({ label: b.label, amount: b.amount, heightPct: Math.max(4, Math.round((b.amount / max) * 100)) }));
  }

  // ── Expiring licenses (next 30 days) ────────────────────────────────────

  get expiringLicenses(): ExpiringItem[] {
    const items: ExpiringItem[] = [];
    for (const lic of this.licenses) {
      if (lic.status !== 'ACTIVE' || !lic.expiresAt) continue;
      const days = Math.round((new Date(lic.expiresAt + 'T00:00:00').getTime() - new Date().setHours(0, 0, 0, 0)) / 86400000);
      if (days < 0 || days > 30) continue;
      const client = this.clients.find(c => c.clientCode === lic.clientCode);
      items.push({
        clientCode: lic.clientCode,
        appName: client?.appName ?? lic.clientCode,
        expiresAt: lic.expiresAt,
        daysLeft: days
      });
    }
    return items.sort((a, b) => a.daysLeft - b.daysLeft).slice(0, 6);
  }

  // ── Recent payments ──────────────────────────────────────────────────────

  get recentPayments(): Billing[] {
    return [...this.billings]
      .sort((a, b) => new Date(b.paymentDate ?? b.createdAt).getTime() - new Date(a.paymentDate ?? a.createdAt).getTime())
      .slice(0, 6);
  }

  clientNameOf(clientCode: string): string {
    return this.clients.find(c => c.clientCode === clientCode)?.appName ?? clientCode;
  }
}

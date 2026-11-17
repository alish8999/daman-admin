import { TranslatePipe } from '../../pipes/translate.pipe';
import { DaysLeftPipe } from '../../pipes/days-left.pipe';
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, catchError, of } from 'rxjs';
import { ClientService } from '../../services/client.service';
import { LicenseService, License } from '../../services/license.service';
import { BillingService } from '../../services/billing.service';
import { TranslationService } from '../../services/translation.service';
import { Billing } from '../../models/billing.model';
import { ClientConfig } from '../../models/client-config.model';
import { computeClientStatus } from '../../models/client-status';

interface MonthBar {
  label: string;
  amount: number;
  count: number;
  heightPct: number;
}

/** One month plotted in the revenue chart's viewBox coordinates. */
export interface ChartPoint {
  x: number;
  y: number;
  label: string;
  amount: number;
  count: number;
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
  imports: [CommonModule, RouterLink, TranslatePipe, DaysLeftPipe],
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
    private billingService: BillingService,
    public translationService: TranslationService
  ) {}

  ngOnInit(): void {
    forkJoin({
      clients: this.clientService.getAll(),
      licenses: this.licenseService.getAll(),
      billings: this.billingService.getAll()
    }).pipe(
      catchError(() => {
        this.loading = false;
        return of(null);
      })
    ).subscribe(result => {
      if (!result) return;
      this.clients = result.clients;
      this.licenses = result.licenses;
      this.billings = result.billings;
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
    const buckets: { key: string; label: string; amount: number; count: number }[] = [];
    for (let i = 5; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      buckets.push({
        key: `${d.getFullYear()}-${d.getMonth()}`,
        // The month names are the only words in the chart, and the owner reads this page in
        // Arabic — a hardcoded 'en' left Jan/Feb/Mar sitting inside an otherwise Arabic card.
        label: d.toLocaleString(this.translationService.getCurrentLanguage(), { month: 'short' }),
        amount: 0,
        count: 0
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
      if (bucket) {
        bucket.amount += b.amount ? +b.amount : 0;
        bucket.count += 1;
      }
    }
    const max = Math.max(1, ...buckets.map(b => b.amount));
    return buckets.map(b => ({
      label: b.label,
      amount: b.amount,
      count: b.count,
      heightPct: Math.max(4, Math.round((b.amount / max) * 100))
    }));
  }

  // ── Expiring licenses (next 30 days) ────────────────────────────────────

  // ── Revenue chart ────────────────────────────────────────────────────────
  //
  // Inline SVG, no chart library — the portal ships no dependency for this.
  //
  // Colour: the brand accent, one series. `validate_palette.js` marks #0a4d68 as
  // outside the categorical lightness/chroma bands, but its own scope note says those
  // checks are for CATEGORICAL palettes — sets of hues that must stay distinguishable
  // from each other. A lone series has no adjacent pair to separate from; the check
  // that applies is contrast against the surface, which PASSES (>= 3:1). Do not
  // "fix" that FAIL by recolouring the chart: the accent is the portal's identity and
  // is pinned by admin-tokens.test.js.

  /** viewBox geometry. Fixed so the path maths is readable; the SVG scales with CSS. */
  readonly chartW = 600;
  readonly chartH = 200;
  private readonly padL = 48;
  private readonly padR = 14;
  private readonly padT = 16;
  private readonly padB = 30;

  /** The highest month, rounded up to something an axis label can say plainly. */
  get chartMax(): number {
    const peak = Math.max(0, ...this.revenueByMonth.map(m => m.amount));
    if (peak <= 0) return 0;
    const magnitude = Math.pow(10, Math.floor(Math.log10(peak)));
    return Math.ceil(peak / magnitude) * magnitude;
  }

  /** Three gridlines: zero, middle, top. More would be clutter at this size. */
  axisTicks(max: number): number[] {
    if (max <= 0) return [0];
    return [0, max / 2, max];
  }

  yOf(amount: number): number {
    const max = this.chartMax;
    const usable = this.chartH - this.padT - this.padB;
    if (max <= 0) return this.chartH - this.padB;
    return this.padT + usable * (1 - amount / max);
  }

  get chartPoints(): ChartPoint[] {
    const months = this.revenueByMonth;
    const span = this.chartW - this.padL - this.padR;
    const step = months.length > 1 ? span / (months.length - 1) : 0;
    return months.map((m, i) => ({
      x: this.padL + step * i,
      y: this.yOf(m.amount),
      label: m.label,
      amount: m.amount,
      count: m.count
    }));
  }

  /**
   * A smooth curve through the points, as cubic segments whose control points sit at the
   * SAME HEIGHT as the endpoints they belong to.
   *
   * <p>That shape is monotone in y by construction, so the curve can never dip below a
   * month's value on its way between two months — which for revenue would draw money that
   * was never taken, and below zero at that. A Catmull-Rom spline reads slightly smoother
   * and does exactly that on a sharp drop, which is why it is not used here.
   */
  get linePath(): string {
    const pts = this.chartPoints;
    if (pts.length === 0) return '';
    let d = `M ${pts[0].x} ${pts[0].y}`;
    for (let i = 1; i < pts.length; i++) {
      const prev = pts[i - 1];
      const cur = pts[i];
      const mid = (prev.x + cur.x) / 2;
      d += ` C ${mid} ${prev.y}, ${mid} ${cur.y}, ${cur.x} ${cur.y}`;
    }
    return d;
  }

  /** The same curve, closed down to the baseline, for the fill. */
  get areaPath(): string {
    const pts = this.chartPoints;
    if (pts.length === 0) return '';
    const baseline = this.chartH - this.padB;
    return `${this.linePath} L ${pts[pts.length - 1].x} ${baseline} L ${pts[0].x} ${baseline} Z`;
  }

  /**
   * The months worth labelling on the plot: the peak and the most recent.
   *
   * <p>A number on every point is the commonest chart mistake — six labels compete with
   * each other and with the axis, and the reader ends up reading none of them. The peak
   * and "now" are the two a person actually looks for.
   */
  get labelledPoints(): ChartPoint[] {
    const pts = this.chartPoints;
    if (pts.length === 0) return [];
    const peak = pts.reduce((a, b) => (b.amount > a.amount ? b : a), pts[0]);
    const last = pts[pts.length - 1];
    return peak === last || peak.amount <= 0 ? [last] : [peak, last];
  }

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

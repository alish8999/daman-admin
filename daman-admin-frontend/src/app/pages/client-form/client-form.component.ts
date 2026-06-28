import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { ClientService } from '../../services/client.service';
import { TranslationService } from '../../services/translation.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { PackageService } from '../../services/package.service';
import { PackageDef, DEFAULT_PACKAGE_DEFINITIONS } from '../../models/package-definition.model';
import { LicenseService, License, GenerateLicenseResponse } from '../../services/license.service';
import { BillingService } from '../../services/billing.service';
import { Billing, BillingRequest } from '../../models/billing.model';

@Component({
  selector: 'app-client-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule, RouterLink, AsyncPipe, TranslatePipe],
  templateUrl: './client-form.component.html'
})
export class ClientFormComponent implements OnInit {
  form!: FormGroup;
  isEditMode = false;
  clientCode: string | null = null;
  saving = false;
  error = '';
  passwordVisible = false;

  // Tab management
  activeTab: 'business' | 'branding' | 'features' | 'billing' | 'license' = 'business';

  // License management
  clientLicenses: License[] = [];
  licensesLoading = false;
  licensesLoaded = false;
  showGenerateForm = false;
  generateForm = { machineId: '', label: '', expiresAt: '' };
  generating = false;
  generatedResult: GenerateLicenseResponse | null = null;
  generateError = '';
  licenseCopied = false;

  licenseRevokeTarget: License | null = null;
  licenseRevoking = false;
  licenseRevokeError = '';

  licenseRenewTarget: { license: License; expiresAt: string } | null = null;
  licenseRenewing = false;
  licenseRenewError = '';

  // Billing management
  clientBillings: Billing[] = [];
  billingsLoading = false;
  billingsLoaded = false;
  showBillingForm = false;
  billingEditTarget: Billing | null = null;
  billingForm = {
    packageTier: '', amount: null as number | null, paymentMethod: 'CASH',
    paymentStatus: 'PENDING', invoiceRef: '', paymentDate: '',
    supportStartDate: '', supportEndDate: '', notes: ''
  };
  billingSaving = false;
  billingError = '';

  readonly storeTypeValues = ['mobile', 'grocery', 'clothing', 'pharmacy', 'hardware', 'bookstore', 'cafe', 'general'];
  readonly baseCurrencyValues = ['USD', 'SYP', 'SYP_OLD'];
  readonly buildTargetValues = ['win', 'win7', 'mac', 'linux'];
  readonly packageTierValues = ['BASIC', 'PRO', 'ULTIMATE'];
  readonly paymentMethodValues = ['CASH', 'SHAM_CASH', 'BANK_TRANSFER', 'WESTERN_UNION', 'OTHER'];
  readonly paymentMethodLabels: Record<string, string> = {
    CASH: 'Cash', SHAM_CASH: 'شام كاش', BANK_TRANSFER: 'Bank Transfer',
    WESTERN_UNION: 'Western Union', OTHER: 'Other'
  };
  readonly paymentStatusValues = ['PAID', 'PENDING', 'PARTIAL'];
  readonly clientStatusValues = ['ACTIVE', 'INACTIVE', 'TRIAL'];
  readonly billingPackageTierValues = ['BASIC', 'PRO', 'ULTIMATE'];

  packageDefs: PackageDef[] = DEFAULT_PACKAGE_DEFINITIONS.packages;

  private readonly featureKeys = [
    'multiLanguage', 'barcode', 'reports', 'suppliers', 'seedDemoData', 'multiCurrency',
    'shifts', 'clientLedger', 'supplierLedger', 'fractionalQuantity', 'multiCurrencyPricing',
    'accountStatement', 'itemLedger', 'batchStocktake', 'bulkPriceUpdate', 'productRecipes',
    'manufacturing', 'userManagement', 'invoiceSettings'
  ];

  readonly colorFields = [
    { key: 'colorPrimary',   labelKey: 'colorPrimary' },
    { key: 'colorSecondary', labelKey: 'colorSecondary' },
    { key: 'colorSuccess',   labelKey: 'colorSuccess' },
    { key: 'colorDanger',    labelKey: 'colorDanger' },
    { key: 'colorWarning',   labelKey: 'colorWarning' },
    { key: 'colorInfo',      labelKey: 'colorInfo' }
  ];

  constructor(
    private fb: FormBuilder,
    private clientService: ClientService,
    private licenseService: LicenseService,
    private billingService: BillingService,
    private route: ActivatedRoute,
    private router: Router,
    private packageService: PackageService,
    public translationService: TranslationService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      clientCode:            ['', Validators.required],
      appName:               ['', Validators.required],
      tagline:               ['', Validators.required],
      logoDark:              ['assets/brand/logo.png', Validators.required],
      logoLight:             ['assets/brand/logo-light.png', Validators.required],
      favicon:               ['favicon.ico'],
      colorPrimary:          ['#667eea', Validators.required],
      colorSecondary:        ['#764ba2', Validators.required],
      colorSuccess:          ['#28a745', Validators.required],
      colorDanger:           ['#dc3545', Validators.required],
      colorWarning:          ['#f59e0b', Validators.required],
      colorInfo:             ['#4facfe', Validators.required],
      footerDeveloper:       ['Eng. Ali Shaaban'],
      footerUrl:             ['https://alish8999.github.io/daman/'],
      storeType:             ['mobile'],
      baseCurrency:          ['USD'],
      dashboardHeaderImage:  [''],
      adminUsername:         ['admin', Validators.required],
      adminPassword:         ['', Validators.required],
      phone:                 [''],
      email:                 [''],
      pointOfContact:        [''],
      defaultBuildTarget:    ['win'],
      packageTier:           [''],
      clientStatus:          ['ACTIVE'],
      clientNotes:           [''],
      features: this.fb.group({
        multiLanguage:        [false],
        barcode:              [false],
        reports:              [false],
        suppliers:            [false],
        seedDemoData:         [false],
        multiCurrency:        [true],
        shifts:               [false],
        clientLedger:         [false],
        supplierLedger:       [false],
        fractionalQuantity:   [false],
        multiCurrencyPricing: [false],
        accountStatement:     [false],
        itemLedger:           [false],
        batchStocktake:       [false],
        bulkPriceUpdate:      [false],
        productRecipes:       [false],
        manufacturing:        [false],
        userManagement:       [false],
        invoiceSettings:      [false]
      })
    });
    this.passwordVisible = false;

    this.packageService.getDefinitions().subscribe({
      next: (d) => { if (d?.packages?.length) this.packageDefs = d.packages; },
      error: () => {}
    });

    this.clientCode = this.route.snapshot.paramMap.get('clientCode');
    this.isEditMode = !!this.clientCode;

    if (this.isEditMode) {
      this.form.get('clientCode')!.disable();
      this.clientService.getOne(this.clientCode!).subscribe(client => {
        this.form.patchValue({
          ...client,
          storeType:         client.storeType         ?? 'mobile',
          baseCurrency:      client.baseCurrency       ?? 'USD',
          phone:             client.phone              ?? '',
          email:             client.email              ?? '',
          pointOfContact:    client.pointOfContact     ?? '',
          defaultBuildTarget: client.defaultBuildTarget ?? 'win',
          packageTier:       client.packageTier        ?? '',
          clientStatus:      client.clientStatus       ?? 'ACTIVE',
          clientNotes:       client.clientNotes        ?? '',
          features: {
            multiLanguage:        client.features?.multiLanguage        ?? false,
            barcode:              client.features?.barcode              ?? false,
            reports:              client.features?.reports              ?? false,
            suppliers:            client.features?.suppliers            ?? false,
            seedDemoData:         client.features?.seedDemoData         ?? false,
            multiCurrency:        client.features?.multiCurrency        ?? true,
            shifts:               client.features?.shifts               ?? false,
            clientLedger:         client.features?.clientLedger         ?? false,
            supplierLedger:       client.features?.supplierLedger       ?? false,
            fractionalQuantity:   client.features?.fractionalQuantity   ?? false,
            multiCurrencyPricing: client.features?.multiCurrencyPricing ?? false,
            accountStatement:     client.features?.accountStatement     ?? false,
            itemLedger:           client.features?.itemLedger           ?? false,
            batchStocktake:       client.features?.batchStocktake       ?? false,
            bulkPriceUpdate:      client.features?.bulkPriceUpdate      ?? false,
            productRecipes:       client.features?.productRecipes       ?? false,
            manufacturing:        client.features?.manufacturing        ?? false,
            userManagement:       client.features?.userManagement       ?? false,
            invoiceSettings:      client.features?.invoiceSettings      ?? false
          }
        });
      });
    }
  }

  // ── Tab helpers ──────────────────────────────────────────────────────────────

  switchTab(tab: 'business' | 'branding' | 'features' | 'billing' | 'license'): void {
    this.activeTab = tab;
    if (tab === 'license' && !this.licensesLoaded) {
      this.loadClientLicenses();
    }
    if (tab === 'billing' && !this.billingsLoaded) {
      this.loadClientBillings();
    }
  }

  // ── Package presets ──────────────────────────────────────────────────────────

  selectPackage(key: string): void {
    const def = this.packageDefs.find(p => p.key === key);
    const on = new Set(def?.features ?? []);
    const features = this.form.get('features') as FormGroup;
    for (const k of this.featureKeys) {
      if (k === 'seedDemoData') continue;
      features.get(k)?.setValue(on.has(k));
    }
    features.markAsDirty();
  }

  get selectedTier(): string | null {
    const features = this.form?.get('features') as FormGroup | null;
    if (!features) return null;
    for (const def of this.packageDefs) {
      const on = new Set(def.features);
      const matches = this.featureKeys.every(key =>
        key === 'seedDemoData' || !!features.get(key)?.value === on.has(key)
      );
      if (matches) return def.key;
    }
    return null;
  }

  // ── Image upload ─────────────────────────────────────────────────────────────

  onImageUpload(field: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    if (file.size > 512 * 1024) {
      this.error = this.translationService.instant('imageTooLarge', {
        name: file.name,
        size: Math.round(file.size / 1024)
      });
    } else {
      this.error = '';
    }

    const reader = new FileReader();
    reader.onload = () => { this.form.get(field)?.setValue(reader.result as string); };
    reader.readAsDataURL(file);
  }

  isDataUrl(value: string | null): boolean {
    return !!value?.startsWith('data:');
  }

  // ── Save ─────────────────────────────────────────────────────────────────────

  save(): void {
    if (this.form.invalid) return;
    this.saving = true;
    this.error = '';

    const data = this.form.getRawValue();

    const request$ = this.isEditMode
      ? this.clientService.update(this.clientCode!, data)
      : this.clientService.create(data);

    request$.subscribe({
      next: () => this.router.navigate(['/clients']),
      error: err => {
        this.error = err?.error?.message || this.translationService.instant('saveFailed');
        this.saving = false;
      }
    });
  }

  // ── License management ────────────────────────────────────────────────────────

  loadClientLicenses(): void {
    if (!this.clientCode) return;
    this.licensesLoading = true;
    this.licenseService.getByClient(this.clientCode).subscribe({
      next: (list) => {
        this.clientLicenses = list.sort((a, b) =>
          new Date(b.activatedAt).getTime() - new Date(a.activatedAt).getTime()
        );
        this.licensesLoading = false;
        this.licensesLoaded = true;
      },
      error: () => { this.licensesLoading = false; this.licensesLoaded = true; }
    });
  }

  openGenerateForm(): void {
    this.showGenerateForm = true;
    this.generateForm = { machineId: '', label: '', expiresAt: '' };
    this.generatedResult = null;
    this.generateError = '';
  }

  cancelGenerateForm(): void {
    this.showGenerateForm = false;
    this.generatedResult = null;
    this.generateError = '';
  }

  generateLicense(): void {
    if (!this.generateForm.machineId.trim() || !this.clientCode) return;
    this.generating = true;
    this.generateError = '';
    this.generatedResult = null;
    this.licenseService.generate({
      machineId: this.generateForm.machineId.trim(),
      clientCode: this.clientCode,
      label: this.generateForm.label.trim() || undefined,
      expiresAt: this.generateForm.expiresAt || undefined
    }).subscribe({
      next: (result) => {
        this.generating = false;
        this.generatedResult = result;
        this.loadClientLicenses();
      },
      error: (err) => {
        this.generating = false;
        this.generateError = err.error?.error || 'Failed to generate license.';
      }
    });
  }

  copyKey(key: string): void {
    navigator.clipboard.writeText(key).then(() => {
      this.licenseCopied = true;
      setTimeout(() => this.licenseCopied = false, 2000);
    });
  }

  openRevokeConfirm(license: License): void {
    this.licenseRevokeTarget = license;
    this.licenseRevokeError = '';
  }

  cancelRevoke(): void {
    this.licenseRevokeTarget = null;
  }

  confirmRevoke(): void {
    if (!this.licenseRevokeTarget || this.licenseRevoking) return;
    this.licenseRevoking = true;
    this.licenseService.revoke(this.licenseRevokeTarget.id).subscribe({
      next: () => {
        this.licenseRevoking = false;
        this.licenseRevokeTarget = null;
        this.loadClientLicenses();
      },
      error: (err) => {
        this.licenseRevoking = false;
        this.licenseRevokeError = err.error?.message || 'Revoke failed.';
      }
    });
  }

  openRenewModal(license: License): void {
    this.licenseRenewTarget = { license, expiresAt: '' };
    this.licenseRenewError = '';
  }

  cancelRenew(): void {
    this.licenseRenewTarget = null;
  }

  confirmRenew(): void {
    if (!this.licenseRenewTarget || this.licenseRenewing) return;
    this.licenseRenewing = true;
    const { license, expiresAt } = this.licenseRenewTarget;
    this.licenseService.renewById(license.id, expiresAt || undefined).subscribe({
      next: () => {
        this.licenseRenewing = false;
        this.licenseRenewTarget = null;
        this.loadClientLicenses();
      },
      error: (err) => {
        this.licenseRenewing = false;
        this.licenseRenewError = err.error?.message || 'Renew failed.';
      }
    });
  }

  deleteLicense(license: License): void {
    if (!confirm(`Delete license record for machine ${this.truncate(license.machineId)}?`)) return;
    this.licenseService.delete(license.id).subscribe(() => this.loadClientLicenses());
  }

  // ── License display helpers ──────────────────────────────────────────────────

  truncate(id: string, len = 24): string {
    return id?.length > len ? id.substring(0, len) + '…' : id;
  }

  isLicenseExpired(l: License): boolean {
    if (!l.expiresAt) return false;
    return new Date(l.expiresAt + 'T00:00:00') < new Date();
  }

  daysUntilExpiry(l: License): number | null {
    if (!l.expiresAt) return null;
    const ms = new Date(l.expiresAt + 'T00:00:00').getTime() - new Date().setHours(0,0,0,0);
    return Math.round(ms / 86400000);
  }

  // ── Billing management ────────────────────────────────────────────────────

  loadClientBillings(): void {
    if (!this.clientCode) return;
    this.billingsLoading = true;
    this.billingService.getByClient(this.clientCode).subscribe({
      next: (list) => {
        this.clientBillings = list;
        this.billingsLoading = false;
        this.billingsLoaded = true;
      },
      error: () => { this.billingsLoading = false; this.billingsLoaded = true; }
    });
  }

  openAddBillingForm(): void {
    this.billingEditTarget = null;
    this.billingForm = {
      packageTier: this.form.get('packageTier')?.value ?? '',
      amount: null, paymentMethod: 'CASH', paymentStatus: 'PENDING',
      invoiceRef: '', paymentDate: '', supportStartDate: '', supportEndDate: '', notes: ''
    };
    this.billingError = '';
    this.showBillingForm = true;
  }

  openEditBillingForm(b: Billing): void {
    this.billingEditTarget = b;
    this.billingForm = {
      packageTier: b.packageTier ?? '',
      amount: b.amount ?? null,
      paymentMethod: b.paymentMethod ?? 'CASH',
      paymentStatus: b.paymentStatus ?? 'PENDING',
      invoiceRef: b.invoiceRef ?? '',
      paymentDate: b.paymentDate ?? '',
      supportStartDate: b.supportStartDate ?? '',
      supportEndDate: b.supportEndDate ?? '',
      notes: b.notes ?? ''
    };
    this.billingError = '';
    this.showBillingForm = true;
  }

  cancelBillingForm(): void {
    this.showBillingForm = false;
    this.billingEditTarget = null;
    this.billingError = '';
  }

  saveBilling(): void {
    if (!this.clientCode) return;
    this.billingSaving = true;
    this.billingError = '';
    const req: BillingRequest = {
      packageTier: this.billingForm.packageTier || undefined,
      amount: this.billingForm.amount ?? undefined,
      paymentMethod: this.billingForm.paymentMethod || undefined,
      paymentStatus: this.billingForm.paymentStatus || undefined,
      invoiceRef: this.billingForm.invoiceRef || undefined,
      paymentDate: this.billingForm.paymentDate || undefined,
      supportStartDate: this.billingForm.supportStartDate || undefined,
      supportEndDate: this.billingForm.supportEndDate || undefined,
      notes: this.billingForm.notes || undefined
    };
    const op$ = this.billingEditTarget
      ? this.billingService.update(this.billingEditTarget.id, req)
      : this.billingService.create(this.clientCode, req);
    op$.subscribe({
      next: () => {
        this.billingSaving = false;
        this.showBillingForm = false;
        this.billingEditTarget = null;
        this.loadClientBillings();
      },
      error: (err) => {
        this.billingSaving = false;
        this.billingError = err.error?.message || 'Save failed.';
      }
    });
  }

  deleteBilling(b: Billing): void {
    if (!confirm(`Delete this billing record (${b.paymentDate ?? b.createdAt.substring(0, 10)})?`)) return;
    this.billingService.delete(b.id).subscribe(() => this.loadClientBillings());
  }

  // ── Billing display helpers ───────────────────────────────────────────────

  printInvoice(b: Billing, lang: 'en' | 'ar'): void {
    const isAr = lang === 'ar';
    const client = this.form.getRawValue();
    // Always format dates with English numerals; use Arabic month names only for Arabic
    const fmt = (d?: string | null) => {
      if (!d) return '—';
      const dt = new Date(d + 'T00:00:00');
      const month = dt.toLocaleString(isAr ? 'ar-SA-u-nu-latn' : 'en-GB', { month: 'long' });
      const day   = String(dt.getDate()).padStart(2, '0');
      const year  = dt.getFullYear();
      return isAr ? `${day} ${month} ${year}` : `${day} ${month} ${year}`;
    };
    const money = (v?: number | null) => v != null ? `$${(+v).toFixed(2)}` : '—';
    // Wrap any string that contains digits/symbols in a ltr span so numbers render correctly in RTL
    const ltr = (s: string) => `<span dir="ltr" style="unicode-bidi:embed">${s}</span>`;

    const L = isAr ? {
      company: 'ضمان',
      subtitle: 'إدارة المبيعات',
      invoice: 'فاتورة',
      invoiceNo: 'رقم الفاتورة',
      issuedOn: 'تاريخ الإصدار',
      clientSection: 'معلومات العميل',
      billingSection: 'تفاصيل الفاتورة',
      date: 'تاريخ الدفع',
      client: 'اسم العميل',
      clientCode: 'رمز العميل',
      contact: 'جهة الاتصال',
      phone: 'الهاتف',
      email: 'البريد الإلكتروني',
      package: 'الباقة',
      amount: 'المبلغ المدفوع',
      paymentMethod: 'طريقة الدفع',
      paymentStatus: 'حالة الدفع',
      supportPeriod: 'فترة الدعم',
      notes: 'ملاحظات',
      ph1: '+971 55 196 2296',
      ph2: '+963 994 065 899',
      thankYou: 'شكراً لثقتكم بنا',
      paid: 'مدفوع', pending: 'معلّق', partial: 'جزئي',
      invoiceRefLabel: 'رقم الفاتورة / المرجع',
      methods: { CASH: 'نقداً', SHAM_CASH: 'شام كاش', BANK_TRANSFER: 'تحويل بنكي', WESTERN_UNION: 'ويسترن يونيون', OTHER: 'أخرى' } as Record<string,string>
    } : {
      company: 'Daman',
      subtitle: 'Sales Management',
      invoice: 'Invoice',
      invoiceNo: 'Invoice #',
      issuedOn: 'Issued on',
      clientSection: 'Client Information',
      billingSection: 'Invoice Details',
      date: 'Payment Date',
      client: 'Client Name',
      clientCode: 'Client Code',
      contact: 'Contact Person',
      phone: 'Phone',
      email: 'Email',
      package: 'Package',
      amount: 'Amount Paid',
      paymentMethod: 'Payment Method',
      paymentStatus: 'Payment Status',
      supportPeriod: 'Support Period',
      notes: 'Notes',
      ph1: '+971 55 196 2296',
      ph2: '+963 994 065 899',
      thankYou: 'Thank you for your business',
      paid: 'PAID', pending: 'PENDING', partial: 'PARTIAL',
      invoiceRefLabel: 'Invoice / Reference #',
      methods: { CASH: 'Cash', SHAM_CASH: 'Sham Cash', BANK_TRANSFER: 'Bank Transfer', WESTERN_UNION: 'Western Union', OTHER: 'Other' } as Record<string,string>
    };

    const statusLabel  = b.paymentStatus === 'PAID' ? L.paid : b.paymentStatus === 'PARTIAL' ? L.partial : L.pending;
    const statusBg     = b.paymentStatus === 'PAID' ? '#16a34a' : b.paymentStatus === 'PARTIAL' ? '#0891b2' : '#d97706';
    const methodLabel  = b.paymentMethod ? (L.methods[b.paymentMethod] ?? b.paymentMethod.replace(/_/g, ' ')) : '—';
    const dir          = isAr ? 'rtl' : 'ltr';
    const align        = isAr ? 'right' : 'left';
    const alignOpp     = isAr ? 'left' : 'right';
    const today        = fmt(new Date().toISOString().slice(0, 10));

    const supportRange = (b.supportStartDate || b.supportEndDate)
      ? `${ltr(fmt(b.supportStartDate))} → ${ltr(fmt(b.supportEndDate))}`
      : '—';

    const row = (label: string, value: string, numericVal = false) =>
      `<tr>
        <td class="lbl">${label}</td>
        <td class="val">${numericVal ? ltr(value) : value}</td>
      </tr>`;

    const html = `<!DOCTYPE html>
<html lang="${lang}" dir="${dir}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${L.invoice} — ${b.invoiceRef ?? ('#' + b.id)}</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    font-family: ${isAr ? "'Segoe UI', 'Tahoma', 'Arial', sans-serif" : "'Segoe UI', 'Inter', 'Arial', sans-serif"};
    font-size: 13px;
    color: #1e293b;
    background: #f1f5f9;
    padding: 32px 16px;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }

  .page {
    max-width: 720px;
    margin: 0 auto;
    background: #fff;
    border-radius: 16px;
    box-shadow: 0 4px 32px rgba(0,0,0,.10);
    overflow: hidden;
  }

  /* ── Header ── */
  .inv-header {
    background: linear-gradient(135deg, #0f172a 0%, #1e3a5f 100%);
    padding: 36px 40px 28px;
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 24px;
    color: #fff;
  }

  .brand-name {
    font-size: 30px;
    font-weight: 900;
    letter-spacing: -1px;
    color: #fff;
    line-height: 1;
  }
  .brand-sub {
    font-size: 12px;
    color: #93c5fd;
    margin-top: 4px;
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: .08em;
  }
  .brand-phones {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .brand-phones a {
    color: #bfdbfe;
    font-size: 12.5px;
    font-weight: 600;
    text-decoration: none;
    direction: ltr;
    unicode-bidi: embed;
    display: inline-block;
  }

  .inv-title-block {
    text-align: ${alignOpp};
  }
  .inv-title-block .word {
    font-size: 36px;
    font-weight: 900;
    color: #fff;
    text-transform: uppercase;
    letter-spacing: 2px;
    opacity: .9;
  }
  .inv-title-block .inv-ref {
    font-size: 13px;
    color: #93c5fd;
    margin-top: 6px;
    direction: ltr;
    unicode-bidi: embed;
    display: block;
  }
  .inv-title-block .issued {
    font-size: 11px;
    color: #60a5fa;
    margin-top: 4px;
    display: block;
  }
  .status-pill {
    display: inline-block;
    margin-top: 10px;
    padding: 5px 16px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: .06em;
    background: ${statusBg};
    color: #fff;
    text-transform: uppercase;
  }

  /* ── Body ── */
  .body { padding: 32px 40px; }

  .section-label {
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: .1em;
    color: #94a3b8;
    margin-bottom: 10px;
    padding-bottom: 6px;
    border-bottom: 1px solid #e2e8f0;
  }

  .card {
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    margin-bottom: 24px;
    overflow: hidden;
  }

  table { width: 100%; border-collapse: collapse; }
  table tr:not(:last-child) td { border-bottom: 1px solid #e2e8f0; }
  td { padding: 10px 16px; vertical-align: middle; }
  td.lbl {
    width: 42%;
    font-size: 11.5px;
    font-weight: 600;
    color: #64748b;
    background: #f1f5f9;
  }
  td.val {
    font-size: 13px;
    font-weight: 500;
    color: #1e293b;
  }
  .amount-row td.val {
    font-size: 20px;
    font-weight: 800;
    color: #0f172a;
    direction: ltr;
    unicode-bidi: embed;
  }
  .amount-row td.lbl { font-size: 12px; }

  /* ── Footer ── */
  .inv-footer {
    background: #f8fafc;
    border-top: 1px solid #e2e8f0;
    padding: 20px 40px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }
  .footer-thank {
    font-size: 13px;
    font-weight: 700;
    color: #1e293b;
  }
  .footer-phones {
    display: flex;
    flex-direction: column;
    align-items: ${alignOpp};
    gap: 3px;
  }
  .footer-phones span {
    font-size: 12px;
    font-weight: 600;
    color: #475569;
    direction: ltr;
    unicode-bidi: embed;
    display: block;
  }

  @media print {
    body { background: #fff; padding: 0; }
    .page { border-radius: 0; box-shadow: none; }
    @page { margin: 10mm 12mm; size: A4; }
  }
</style>
</head>
<body>
<div class="page">

  <!-- Header -->
  <div class="inv-header">
    <div class="brand-side">
      <div class="brand-name">${L.company}</div>
      <div class="brand-sub">${L.subtitle}</div>
      <div class="brand-phones">
        <a>${ltr(L.ph1)}</a>
        <a>${ltr(L.ph2)}</a>
      </div>
    </div>
    <div class="inv-title-block">
      <div class="word">${L.invoice}</div>
      <span class="inv-ref">${L.invoiceNo} ${ltr(b.invoiceRef ?? ('#' + b.id))}</span>
      <span class="issued">${L.issuedOn}: ${ltr(today)}</span>
      <div><span class="status-pill">${statusLabel}</span></div>
    </div>
  </div>

  <!-- Body -->
  <div class="body">

    <!-- Client info -->
    <div class="section-label">${L.clientSection}</div>
    <div class="card">
      <table>
        ${row(L.client, client.appName ?? '—')}
        ${row(L.clientCode, this.clientCode ?? '—', true)}
        ${client.pointOfContact ? row(L.contact, client.pointOfContact) : ''}
        ${client.phone ? row(L.phone, ltr(client.phone)) : ''}
        ${client.email ? row(L.email, ltr(client.email)) : ''}
      </table>
    </div>

    <!-- Invoice details -->
    <div class="section-label">${L.billingSection}</div>
    <div class="card">
      <table>
        ${b.packageTier ? row(L.package, b.packageTier) : ''}
        ${row(L.date, ltr(fmt(b.paymentDate)))}
        ${row(L.paymentMethod, methodLabel)}
        ${b.invoiceRef ? row(L.invoiceRefLabel, ltr(b.invoiceRef), true) : ''}
        ${row(L.paymentStatus, statusLabel)}
        ${row(L.supportPeriod, supportRange)}
        ${b.notes ? row(L.notes, b.notes) : ''}
        <tr class="amount-row">
          <td class="lbl">${L.amount}</td>
          <td class="val">${money(b.amount)}</td>
        </tr>
      </table>
    </div>

  </div>

  <!-- Footer -->
  <div class="inv-footer">
    <div class="footer-thank">${L.thankYou}</div>
    <div class="footer-phones">
      <span>${ltr(L.ph1)}</span>
      <span>${ltr(L.ph2)}</span>
    </div>
  </div>

</div>
<script>window.onload = () => { window.print(); }<\/script>
</body>
</html>`;

    const w = window.open('', '_blank', 'width=800,height=960');
    if (w) { w.document.write(html); w.document.close(); }
  }

  billingPaymentBadge(status?: string | null): string {
    if (status === 'PAID') return 'bg-success';
    if (status === 'PARTIAL') return 'bg-info text-dark';
    return 'bg-warning text-dark';
  }

  billingSupportDaysLeft(b: Billing): number | null {
    if (!b.supportEndDate) return null;
    const ms = new Date(b.supportEndDate + 'T00:00:00').getTime() - new Date().setHours(0,0,0,0);
    return Math.round(ms / 86400000);
  }

  get totalBillingPaid(): number {
    return this.clientBillings
      .filter(b => b.paymentStatus === 'PAID')
      .reduce((s, b) => s + (b.amount ? +b.amount : 0), 0);
  }

  get latestBilling(): Billing | null {
    return this.clientBillings[0] ?? null;
  }
}

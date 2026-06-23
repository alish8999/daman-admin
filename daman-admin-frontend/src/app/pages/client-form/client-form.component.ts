import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { ClientService } from '../../services/client.service';
import { TranslationService } from '../../services/translation.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { PackageService } from '../../services/package.service';
import { PackageDef, DEFAULT_PACKAGE_DEFINITIONS } from '../../models/package-definition.model';

@Component({
  selector: 'app-client-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink, AsyncPipe, TranslatePipe],
  templateUrl: './client-form.component.html'
})
export class ClientFormComponent implements OnInit {
  form!: FormGroup;
  isEditMode = false;
  clientCode: string | null = null;
  saving = false;
  error = '';
  passwordVisible = false;

  readonly storeTypeValues = ['mobile', 'grocery', 'clothing', 'pharmacy', 'hardware', 'bookstore', 'cafe', 'general'];
  readonly baseCurrencyValues = ['USD', 'SYP', 'SYP_OLD'];
  readonly buildTargetValues = ['win', 'win7', 'mac', 'linux'];

  /**
   * Package presets shown as cards above the feature toggles. Loaded from the
   * admin Packages settings; falls back to the built-in defaults if the API is
   * unreachable. The card iterates this list and uses `package.<key>` /
   * `packageDesc.<key>` translation keys for display.
   */
  packageDefs: PackageDef[] = DEFAULT_PACKAGE_DEFINITIONS.packages;

  /** Every feature flag key, in the order they appear in the form. */
  private readonly featureKeys = [
    'multiLanguage', 'barcode', 'reports', 'suppliers', 'seedDemoData', 'multiCurrency',
    'shifts', 'clientLedger', 'supplierLedger', 'fractionalQuantity', 'multiCurrencyPricing',
    'accountStatement', 'itemLedger', 'batchStocktake', 'bulkPriceUpdate', 'productRecipes',
    'manufacturing'
  ];

  readonly colorFields = [
    { key: 'colorPrimary', labelKey: 'colorPrimary' },
    { key: 'colorSecondary', labelKey: 'colorSecondary' },
    { key: 'colorSuccess', labelKey: 'colorSuccess' },
    { key: 'colorDanger', labelKey: 'colorDanger' },
    { key: 'colorWarning', labelKey: 'colorWarning' },
    { key: 'colorInfo', labelKey: 'colorInfo' }
  ];

  constructor(
    private fb: FormBuilder,
    private clientService: ClientService,
    private route: ActivatedRoute,
    private router: Router,
    private packageService: PackageService,
    public translationService: TranslationService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      clientCode: ['', Validators.required],
      appName: ['', Validators.required],
      tagline: ['', Validators.required],
      logoDark: ['assets/brand/logo.png', Validators.required],
      logoLight: ['assets/brand/logo-light.png', Validators.required],
      favicon: ['favicon.ico'],
      colorPrimary: ['#667eea', Validators.required],
      colorSecondary: ['#764ba2', Validators.required],
      colorSuccess: ['#28a745', Validators.required],
      colorDanger: ['#dc3545', Validators.required],
      colorWarning: ['#f59e0b', Validators.required],
      colorInfo: ['#4facfe', Validators.required],
      footerDeveloper: ['Eng. Ali Shaaban'],
      footerUrl: ['https://alish8999.github.io/daman/'],
      storeType: ['mobile'],
      baseCurrency: ['USD'],
      dashboardHeaderImage: [''],
      adminUsername: ['admin', Validators.required],
      adminPassword: ['', Validators.required],
      phone: [''],
      email: [''],
      pointOfContact: [''],
      defaultBuildTarget: ['win'],
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
        manufacturing:        [false]
      })
    });
    this.passwordVisible = false;

    // Load package presets from the admin Packages settings; keep built-in
    // defaults if the request fails.
    this.packageService.getDefinitions().subscribe({
      next: (d) => { if (d?.packages?.length) this.packageDefs = d.packages; },
      error: () => { /* keep DEFAULT_PACKAGE_DEFINITIONS */ }
    });

    this.clientCode = this.route.snapshot.paramMap.get('clientCode');
    this.isEditMode = !!this.clientCode;

    if (this.isEditMode) {
      this.form.get('clientCode')!.disable();
      this.clientService.getOne(this.clientCode!).subscribe(client => {
        this.form.patchValue({
          ...client,
          storeType: client.storeType ?? 'mobile',
          baseCurrency: client.baseCurrency ?? 'USD',
          phone: client.phone ?? '',
          email: client.email ?? '',
          pointOfContact: client.pointOfContact ?? '',
          defaultBuildTarget: client.defaultBuildTarget ?? 'win',
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
            manufacturing:        client.features?.manufacturing        ?? false
          }
        });
      });
    }
  }

  /**
   * Applies a package preset to the feature toggles. Features in the tier are
   * turned ON, all others (except `seedDemoData`) OFF. The toggles remain fully
   * editable afterward, so the user can opt into extra features on top of a tier.
   */
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

  /**
   * The tier whose preset exactly matches the current toggles, or `null` when
   * the selection is custom. `seedDemoData` is ignored in the comparison.
   * Used to highlight the active package card in the template.
   */
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
    reader.onload = () => {
      this.form.get(field)?.setValue(reader.result as string);
    };
    reader.readAsDataURL(file);
  }

  isDataUrl(value: string | null): boolean {
    return !!value?.startsWith('data:');
  }

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
}

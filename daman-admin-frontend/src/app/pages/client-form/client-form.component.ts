import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ClientService } from '../../services/client.service';

@Component({
  selector: 'app-client-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './client-form.component.html'
})
export class ClientFormComponent implements OnInit {
  form!: FormGroup;
  isEditMode = false;
  clientCode: string | null = null;
  saving = false;
  error = '';
  passwordVisible = false;

  readonly storeTypes = [
    { value: 'mobile',    label: '📱 Mobile & Electronics' },
    { value: 'grocery',   label: '🛒 Grocery & Supermarket' },
    { value: 'clothing',  label: '👕 Clothing & Fashion' },
    { value: 'pharmacy',  label: '💊 Pharmacy & Health' },
    { value: 'hardware',  label: '🔧 Hardware & Tools' },
    { value: 'bookstore', label: '📚 Books & Stationery' },
    { value: 'cafe',      label: '☕ Café & Food Service' },
    { value: 'general',   label: '🏪 General Retail' }
  ];

  readonly baseCurrencies = [
    { value: 'USD',     label: 'USD — US Dollar' },
    { value: 'SYP',     label: 'SYP — Syrian Pound (New)' },
    { value: 'SYP_OLD', label: 'SYP_OLD — Syrian Pound (Old)' }
  ];

  constructor(
    private fb: FormBuilder,
    private clientService: ClientService,
    private route: ActivatedRoute,
    private router: Router
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
        itemLedger:           [false]
      })
    });
    this.passwordVisible = false;

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
            itemLedger:           client.features?.itemLedger           ?? false
          }
        });
      });
    }
  }

  onImageUpload(field: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    if (file.size > 512 * 1024) {
      this.error = `"${file.name}" is ${Math.round(file.size / 1024)}KB. Use an image under 512KB for best results.`;
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
        this.error = err?.error?.message || 'Save failed. Please try again.';
        this.saving = false;
      }
    });
  }
}

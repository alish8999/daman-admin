import { Component, OnInit } from '@angular/core';
import { CommonModule, AsyncPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslationService } from '../../services/translation.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { PackageService } from '../../services/package.service';
import {
  PackageDefinitions,
  PACKAGE_FEATURE_KEYS,
  DEFAULT_PACKAGE_DEFINITIONS
} from '../../models/package-definition.model';

@Component({
  selector: 'app-packages',
  standalone: true,
  imports: [CommonModule, AsyncPipe, RouterLink, TranslatePipe],
  templateUrl: './packages.component.html'
})
export class PackagesComponent implements OnInit {
  readonly featureKeys = PACKAGE_FEATURE_KEYS;
  defs: PackageDefinitions = { packages: [] };
  loading = true;
  saving = false;
  saved = false;
  error = '';

  constructor(
    public translationService: TranslationService,
    private packageService: PackageService
  ) {}

  ngOnInit(): void {
    this.packageService.getDefinitions().subscribe({
      next: (d) => {
        this.defs = d?.packages?.length ? d : structuredClone(DEFAULT_PACKAGE_DEFINITIONS);
        this.loading = false;
      },
      error: () => {
        this.defs = structuredClone(DEFAULT_PACKAGE_DEFINITIONS);
        this.loading = false;
      }
    });
  }

  /** i18n label key for a feature, e.g. multiLanguage → featMultiLanguage. */
  featLabelKey(feature: string): string {
    return 'feat' + feature.charAt(0).toUpperCase() + feature.slice(1);
  }

  hasFeature(pkgKey: string, feature: string): boolean {
    const p = this.defs.packages.find(x => x.key === pkgKey);
    return !!p && p.features.includes(feature);
  }

  toggleFeature(pkgKey: string, feature: string, checked: boolean): void {
    const p = this.defs.packages.find(x => x.key === pkgKey);
    if (!p) return;
    const set = new Set(p.features);
    if (checked) set.add(feature); else set.delete(feature);
    // keep features in canonical order regardless of toggle sequence
    p.features = this.featureKeys.filter(k => set.has(k));
    this.saved = false;
  }

  save(): void {
    this.saving = true;
    this.error = '';
    this.saved = false;
    this.packageService.saveDefinitions(this.defs).subscribe({
      next: () => { this.saving = false; this.saved = true; },
      error: (err) => {
        this.saving = false;
        this.error = err?.error?.message || this.translationService.instant('saveFailed');
      }
    });
  }

  resetDefaults(): void {
    this.defs = structuredClone(DEFAULT_PACKAGE_DEFINITIONS);
    this.saved = false;
  }
}

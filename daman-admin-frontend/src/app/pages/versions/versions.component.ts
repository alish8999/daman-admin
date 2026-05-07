import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AppVersionService, AppVersion, AppVersionRequest } from '../../services/app-version.service';

@Component({
  selector: 'app-versions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './versions.component.html'
})
export class VersionsComponent implements OnInit {
  versions: AppVersion[] = [];

  showModal = false;
  editingId: number | null = null;
  form: AppVersionRequest = { versionNumber: '', releaseDate: '', changelogText: '' };
  saving = false;
  error = '';

  constructor(private versionService: AppVersionService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.versionService.getAll().subscribe(data => this.versions = data);
  }

  openCreate(): void {
    this.editingId = null;
    this.form = { versionNumber: '', releaseDate: new Date().toISOString().split('T')[0], changelogText: '' };
    this.error = '';
    this.showModal = true;
  }

  openEdit(v: AppVersion): void {
    this.editingId = v.id;
    this.form = { versionNumber: v.versionNumber, releaseDate: v.releaseDate, changelogText: v.changelogText ?? '' };
    this.error = '';
    this.showModal = true;
  }

  closeModal(): void {
    this.showModal = false;
  }

  save(): void {
    if (!this.form.versionNumber.trim() || !this.form.releaseDate) {
      this.error = 'Version number and release date are required.';
      return;
    }
    this.saving = true;
    this.error = '';

    const req$ = this.editingId != null
      ? this.versionService.update(this.editingId, this.form)
      : this.versionService.create(this.form);

    req$.subscribe({
      next: () => {
        this.saving = false;
        this.showModal = false;
        this.load();
      },
      error: (err) => {
        this.saving = false;
        this.error = err.error?.message || err.error?.error || 'Save failed.';
      }
    });
  }

  delete(v: AppVersion): void {
    if (!confirm(`Delete version "${v.versionNumber}"? This cannot be undone.`)) return;
    this.versionService.delete(v.id).subscribe(() => this.load());
  }
}

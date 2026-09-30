import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AppVersionService, AppVersion, AppVersionRequest } from '../../services/app-version.service';
import { ChangelogPickerComponent } from '../../components/changelog-picker/changelog-picker.component';

@Component({
  selector: 'app-versions',
  standalone: true,
  imports: [CommonModule, FormsModule, ChangelogPickerComponent],
  templateUrl: './versions.component.html'
})
export class VersionsComponent implements OnInit {
  versions: AppVersion[] = [];

  showModal = false;
  editingId: number | null = null;
  form: AppVersionRequest = { versionNumber: '', releaseDate: '', changelogText: '' };
  saving = false;
  error = '';

  showChangelogPicker = false;

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
        this.error = err.error?.message || 'Save failed.';
      }
    });
  }

  delete(v: AppVersion): void {
    if (!confirm(`Delete version "${v.versionNumber}"? This cannot be undone.`)) return;
    this.versionService.delete(v.id).subscribe(() => this.load());
  }

  /** Composes the checked library entries into the version form's changelog field — replaces
   *  whatever was there, since Submit is meant to build the field from scratch. */
  onChangelogSubmitted(text: string): void {
    this.form.changelogText = text;
    this.showChangelogPicker = false;
  }
}

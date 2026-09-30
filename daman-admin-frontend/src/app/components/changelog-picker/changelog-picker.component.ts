import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChangelogLibraryService, ChangelogEntry } from '../../services/changelog-library.service';

/**
 * "Select Changelog" popup — checkbox list over the shared reusable-bullet
 * library, with inline add/edit/remove, used both from the Versions page and
 * from the Clients page's Confirm Build modal (the moment a version number is
 * actually chosen, which is the one every real release already goes through —
 * direct feedback: put it there instead of a separate page nobody visits).
 * Library edits persist immediately via ChangelogLibraryService; selecting
 * entries and clicking Submit only affects the caller's own changelog text,
 * via the `submitted` output.
 *
 * Every entry carries both an English and an Arabic version — direct request:
 * "we should has also arabic version of them and the client app show
 * according to his selected language". Submit composes BOTH languages into
 * one `[EN]`/`[AR]`-delimited block (see submit()) rather than picking one,
 * so whatever eventually reads AppVersion.changelogText (the shipped app's
 * update badge) can split it and show the client's own language.
 */
@Component({
  selector: 'app-changelog-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './changelog-picker.component.html'
})
export class ChangelogPickerComponent implements OnInit {
  @Output() submitted = new EventEmitter<string>();
  @Output() cancelled = new EventEmitter<void>();

  library: ChangelogEntry[] = [];
  selectedIds = new Set<string>();
  loading = false;
  saving = false;
  error = '';
  newEntryEn = '';
  newEntryAr = '';
  editingEntryId: string | null = null;
  editingEntryEn = '';
  editingEntryAr = '';

  constructor(private changelogLibraryService: ChangelogLibraryService) {}

  ngOnInit(): void {
    this.loading = true;
    this.changelogLibraryService.getAll().subscribe({
      next: (entries) => {
        this.library = entries;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.message || this.describeHttpError(err);
        this.loading = false;
      }
    });
  }

  cancel(): void {
    this.cancelled.emit();
  }

  toggleEntrySelected(id: string): void {
    if (this.selectedIds.has(id)) {
      this.selectedIds.delete(id);
    } else {
      this.selectedIds.add(id);
    }
  }

  startEditEntry(entry: ChangelogEntry): void {
    this.editingEntryId = entry.id;
    this.editingEntryEn = entry.en;
    this.editingEntryAr = entry.ar;
  }

  cancelEditEntry(): void {
    this.editingEntryId = null;
    this.editingEntryEn = '';
    this.editingEntryAr = '';
  }

  saveEditEntry(): void {
    if (!this.editingEntryEn.trim() || !this.editingEntryAr.trim() || this.editingEntryId == null) return;
    const entry = this.library.find(e => e.id === this.editingEntryId);
    if (!entry) return;
    entry.en = this.editingEntryEn.trim();
    entry.ar = this.editingEntryAr.trim();
    this.editingEntryId = null;
    this.persistLibrary();
  }

  deleteEntry(id: string): void {
    if (!confirm('Remove this changelog entry from the library?')) return;
    this.library = this.library.filter(e => e.id !== id);
    this.selectedIds.delete(id);
    this.persistLibrary();
  }

  addNewEntry(): void {
    const en = this.newEntryEn.trim();
    const ar = this.newEntryAr.trim();
    if (!en || !ar) return;
    const id = 'entry-' + Date.now().toString(36);
    this.library = [...this.library, { id, en, ar }];
    this.selectedIds.add(id);
    this.newEntryEn = '';
    this.newEntryAr = '';
    this.persistLibrary();
  }

  /** Distinguishes "server reachable but rejected the request" (401 = expired
   *  session, gets a real body normally handled above) from "couldn't reach
   *  it at all" — the previous version showed the exact same generic string
   *  either way, which made this impossible to diagnose. */
  private describeHttpError(err: { status?: number }): string {
    if (err.status === 0) return 'Could not reach the admin backend — check it is running and reachable.';
    if (err.status === 401) return 'Session expired or invalid — please log in again.';
    return `Could not load the changelog library (HTTP ${err.status ?? '?'}).`;
  }

  private persistLibrary(): void {
    this.saving = true;
    this.changelogLibraryService.saveAll(this.library).subscribe({
      next: () => { this.saving = false; },
      error: (err) => {
        this.saving = false;
        this.error = err.error?.message || this.describeHttpError(err);
      }
    });
  }

  /**
   * `[EN]`/`[AR]` markers rather than JSON — stays human-readable wherever
   * AppVersion.changelogText ends up shown as-is today (the Confirm Build
   * preview, changelog.txt), while still being trivial for later code (the
   * eventual update-badge wiring) to split on and pick a language from.
   */
  submit(): void {
    const chosen = this.library.filter(e => this.selectedIds.has(e.id));
    const enLines = chosen.map(e => '- ' + e.en).join('\n');
    const arLines = chosen.map(e => '- ' + e.ar).join('\n');
    this.submitted.emit(`[EN]\n${enLines}\n\n[AR]\n${arLines}`);
  }
}

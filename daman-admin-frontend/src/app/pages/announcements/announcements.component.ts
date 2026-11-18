import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Announcement, AnnouncementRequest, AnnouncementService } from '../../services/announcement.service';
import { FEATURE_CATALOG } from '../../models/feature-catalog';
import { FeaturePriceService } from '../../services/feature-price.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TranslationService } from '../../services/translation.service';

/**
 * Where the owner writes what every shop sees on its features page.
 *
 * <p>The editor is deliberately plain. The one thing it has to say out loud is what publishing
 * costs: an edit to a published announcement moves its timestamp, which raises the "something
 * new" dot on every till again. That is occasionally what you want and usually not, and it is
 * invisible from here, so the page says it beside the toggle rather than leaving it to be
 * discovered.
 *
 * <p>The picture preview loads from the PUBLIC path on purpose — previewing anything else would
 * not be previewing what the shops get.
 */
@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslatePipe],
  templateUrl: './announcements.component.html'
})
export class AnnouncementsComponent implements OnInit {

  announcements: Announcement[] = [];
  loading = true;
  error = '';
  savedMsg = '';

  /** The row being edited, or a blank draft. Null means the editor is closed. */
  draft: AnnouncementRequest | null = null;
  editingId: number | null = null;
  saving = false;

  deleteTarget: Announcement | null = null;
  deleting = false;

  uploadingFor: number | null = null;
  uploadError = '';

  /** Stated in the UI rather than discovered by a rejected upload. */
  readonly imageMaxKb = 512;
  readonly featureCatalog = FEATURE_CATALOG;

  constructor(private service: AnnouncementService,
              public featurePrices: FeaturePriceService,
              public translationService: TranslationService) {}

  ngOnInit(): void {
    this.featurePrices.load();
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.getAll().subscribe({
      next: (rows) => { this.announcements = rows; this.loading = false; },
      error: (err) => {
        this.error = err?.error?.error || this.translationService.instant('announcementsLoadFailed');
        this.loading = false;
      }
    });
  }

  // ── Editor ───────────────────────────────────────────────────────────────────

  newDraft(): void {
    this.editingId = null;
    this.error = '';
    this.draft = {
      slug: '', titleEn: '', titleAr: '', bodyEn: '', bodyAr: '',
      featureKey: null, sortOrder: 0, published: false
    };
  }

  edit(a: Announcement): void {
    this.editingId = a.id;
    this.error = '';
    this.draft = {
      slug: a.slug, titleEn: a.titleEn, titleAr: a.titleAr,
      bodyEn: a.bodyEn, bodyAr: a.bodyAr, featureKey: a.featureKey,
      sortOrder: a.sortOrder, published: a.published
    };
  }

  cancelEdit(): void {
    this.draft = null;
    this.editingId = null;
  }

  save(): void {
    if (!this.draft || this.saving) return;
    this.saving = true;
    this.error = '';
    const done = (saved: Announcement) => {
      this.saving = false;
      this.draft = null;
      this.editingId = null;
      this.savedMsg = this.translationService.instant('announcementSaved');
      setTimeout(() => this.savedMsg = '', 3000);
      this.load();
    };
    const failed = (err: any) => {
      this.saving = false;
      this.error = err?.error?.error || this.translationService.instant('saveFailed');
    };
    if (this.editingId === null) {
      this.service.create(this.draft).subscribe({ next: done, error: failed });
    } else {
      this.service.update(this.editingId, this.draft).subscribe({ next: done, error: failed });
    }
  }

  /** True once the draft has the one field that cannot be blank. */
  get draftIsSavable(): boolean {
    return !!this.draft && !!this.draft.slug.trim();
  }

  // ── Picture ──────────────────────────────────────────────────────────────────

  onImagePicked(a: Announcement, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploadError = '';
    // Checked here as well as on the server: a rejected 512 KB upload is a wasted round trip on
    // a connection that may not be fast, and the message is clearer before it is sent.
    if (file.size > this.imageMaxKb * 1024) {
      this.uploadError = this.translationService.instant('announcementImageTooBig',
        { kb: String(Math.round(file.size / 1024)), max: String(this.imageMaxKb) });
      input.value = '';
      return;
    }
    this.uploadingFor = a.id;
    this.service.uploadImage(a.id, file).subscribe({
      next: () => { this.uploadingFor = null; input.value = ''; this.load(); },
      error: (err) => {
        this.uploadingFor = null;
        input.value = '';
        this.uploadError = err?.error?.error || this.translationService.instant('saveFailed');
      }
    });
  }

  removeImage(a: Announcement): void {
    this.service.removeImage(a.id).subscribe({ next: () => this.load() });
  }

  /** Cache-busted on updatedAt so a replaced picture is not served from the browser's cache. */
  imageUrl(a: Announcement): string {
    return this.service.imageUrl(a.slug, a.updatedAt);
  }

  // ── Delete ───────────────────────────────────────────────────────────────────

  askDelete(a: Announcement): void {
    this.deleteTarget = a;
  }

  cancelDelete(): void {
    this.deleteTarget = null;
  }

  confirmDelete(): void {
    if (!this.deleteTarget || this.deleting) return;
    this.deleting = true;
    this.service.delete(this.deleteTarget.id).subscribe({
      next: () => { this.deleting = false; this.deleteTarget = null; this.load(); },
      error: (err) => {
        this.deleting = false;
        this.error = err?.error?.error || this.translationService.instant('saveFailed');
      }
    });
  }

  // ── Display helpers ──────────────────────────────────────────────────────────

  featureLabelKey(key: string | null): string | null {
    if (!key) return null;
    return FEATURE_CATALOG.find(f => f.key === key)?.labelKey ?? key;
  }

  /** The badge a shop will see, resolved from the catalogue — never stored on the row. */
  priceBadge(key: string | null): string | null {
    if (!key) return null;
    const price = this.featurePrices.priceOf(key);
    if (price === null) return null;
    return price === 0 ? this.translationService.instant('free') : `$${price}`;
  }
}

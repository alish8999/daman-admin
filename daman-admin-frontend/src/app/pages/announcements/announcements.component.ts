import { Component, OnDestroy, OnInit } from '@angular/core';
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
export class AnnouncementsComponent implements OnInit, OnDestroy {

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

  /** The row whose publish button is mid-flight, so only that one button shows a spinner. */
  publishingFor: number | null = null;

  /** Stated in the UI rather than discovered by a rejected upload. */
  readonly imageMaxKb = 512;
  readonly featureCatalog = FEATURE_CATALOG;

  constructor(private service: AnnouncementService,
              public featurePrices: FeaturePriceService,
              public translationService: TranslationService) {}

  ngOnInit(): void {
    this.featurePrices.load();
    this.load();
    this.loadAiBudget();
  }

  load(): void {
    this.loading = true;
    this.service.getAll().subscribe({
      next: (rows) => { this.announcements = rows; this.loading = false; this.loadPreviews(rows); },
      error: (err) => {
        this.error = err?.error?.error || this.translationService.instant('announcementsLoadFailed');
        this.loading = false;
      }
    });
  }

  // ── AI ───────────────────────────────────────────────────────────────────────
  // Two buttons that spend money. What is left today is shown before either is pressed,
  // rather than a 429 being explained after the fact.

  aiBudget: { text: number; images: number; textPerDay: number; imagesPerDay: number } | null = null;
  aiIntent = '';
  aiDrafting = false;
  aiError = '';

  aiImageFor: number | null = null;
  aiImageBrief = '';
  aiDrawing = false;

  private loadAiBudget(): void {
    this.service.aiBudget().subscribe({
      next: (b) => this.aiBudget = b,
      error: () => this.aiBudget = null
    });
  }

  /**
   * Fills the open draft's four text fields. It never saves and never publishes — the owner
   * edits what comes back and presses Save like any other draft, which is what makes a
   * mediocre answer cheap rather than dangerous.
   */
  writeWithAi(): void {
    if (!this.draft || this.aiDrafting || !this.aiIntent.trim()) return;
    this.aiDrafting = true;
    this.aiError = '';
    this.service.aiDraft(this.aiIntent.trim(), this.draft.featureKey).subscribe({
      next: (d) => {
        this.aiDrafting = false;
        if (!this.draft) return;
        this.draft.titleEn = d.titleEn;
        this.draft.titleAr = d.titleAr;
        this.draft.bodyEn = d.bodyEn;
        this.draft.bodyAr = d.bodyAr;
        this.loadAiBudget();
      },
      error: (err) => {
        this.aiDrafting = false;
        this.aiError = err?.error?.error || this.translationService.instant('aiFailed');
        this.loadAiBudget();
      }
    });
  }

  /**
   * Opens the drawing modal with the announcement's own words already in the brief.
   *
   * <p>Typing the announcement out a second time is the obvious thing to ask of whoever presses
   * this button, and it is the wrong thing: the picture is FOR this announcement, so its text is
   * the brief until the owner decides otherwise. It stays fully editable — nothing is sent until
   * the draw button is pressed.
   *
   * <p>English is preferred because that is what the drawing model reads best, with the Arabic
   * as the fallback rather than nothing at all.
   */
  openAiImage(a: Announcement): void {
    this.aiImageFor = a.id;
    this.aiImageBrief = [a.titleEn || a.titleAr, a.bodyEn || a.bodyAr]
      .map(s => (s || '').trim())
      .filter(s => s.length > 0)
      .join('\n');
    this.aiError = '';
  }

  cancelAiImage(): void {
    this.aiImageFor = null;
  }

  drawWithAi(): void {
    if (this.aiImageFor === null || this.aiDrawing || !this.aiImageBrief.trim()) return;
    this.aiDrawing = true;
    this.aiError = '';
    this.service.aiImage(this.aiImageFor, this.aiImageBrief.trim()).subscribe({
      next: () => {
        this.aiDrawing = false;
        this.aiImageFor = null;
        this.loadAiBudget();
        this.load();
      },
      error: (err) => {
        this.aiDrawing = false;
        this.aiError = err?.error?.error || this.translationService.instant('aiFailed');
        this.loadAiBudget();
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

  /**
   * Whether the row currently open in the editor is already in front of the shops — which is
   * what decides if Save carries the "this re-rings every till" warning.
   */
  get editingRowIsPublished(): boolean {
    return this.editingId !== null
        && this.announcements.some(a => a.id === this.editingId && a.published);
  }

  /** True once the draft has the one field that cannot be blank. */
  get draftIsSavable(): boolean {
    return !!this.draft && !!this.draft.slug.trim();
  }

  // ── Publish ──────────────────────────────────────────────────────────────────

  /**
   * Flips one row between draft and published, from the list.
   *
   * <p>This used to be a checkbox inside the editor, which made putting an announcement in
   * front of the shops a three-step job — open the form, tick, save — two steps of which are
   * about editing text nobody wanted to edit. It is a state the row is in, so it sits on the
   * row next to Edit and Delete.
   *
   * <p>No confirmation dialog. The button says which way it goes, the pill next to it says
   * where the row ended up, and the opposite button is right there — a thing you can undo by
   * pressing the same place twice does not need to be asked about.
   */
  togglePublish(a: Announcement): void {
    if (this.publishingFor !== null) return;
    this.publishingFor = a.id;
    this.error = '';
    this.service.setPublished(a.id, !a.published).subscribe({
      next: (saved) => {
        this.publishingFor = null;
        // Patched in place rather than reloading the list: a reload rebuilds every preview
        // blob, so the pictures would blink on a press that changed one flag.
        a.published = saved.published;
        a.updatedAt = saved.updatedAt;
        this.savedMsg = this.translationService.instant(
          saved.published ? 'announcementPublishedMsg' : 'announcementUnpublishedMsg');
        setTimeout(() => this.savedMsg = '', 3000);
      },
      error: (err) => {
        this.publishingFor = null;
        this.error = err?.error?.error || this.translationService.instant('saveFailed');
      }
    });
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

  /**
   * One object URL per row that has a picture, rebuilt on every load.
   *
   * <p>The admin image path needs the bearer token, and an `<img src>` sends no headers — so
   * the bytes come through HttpClient and become a blob URL. Which also settles the cache
   * question the old `?v=updatedAt` was there to answer: a fresh blob every load is, by
   * construction, never a stale one.
   */
  previewUrls: Record<number, string> = {};

  private loadPreviews(rows: Announcement[]): void {
    this.revokePreviews();
    for (const a of rows) {
      if (!a.hasImage) continue;
      this.service.imageBlob(a.id).subscribe({
        next: (blob) => this.previewUrls[a.id] = URL.createObjectURL(blob),
        // A preview that will not load is not worth an error banner over the whole page; the
        // card simply shows no picture, which is what a missing one looks like anyway.
        error: () => delete this.previewUrls[a.id]
      });
    }
  }

  /** Object URLs pin their blob in memory until revoked, so every replaced one is released. */
  private revokePreviews(): void {
    for (const url of Object.values(this.previewUrls)) URL.revokeObjectURL(url);
    this.previewUrls = {};
  }

  ngOnDestroy(): void {
    this.revokePreviews();
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

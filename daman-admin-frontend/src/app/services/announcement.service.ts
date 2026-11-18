import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** One announcement as the portal sees it — everything except the picture bytes. */
export interface Announcement {
  id: number;
  slug: string;
  titleEn: string | null;
  titleAr: string | null;
  bodyEn: string | null;
  bodyAr: string | null;
  /** A catalogue key, or null for an announcement that sells nothing. */
  featureKey: string | null;
  sortOrder: number;
  published: boolean;
  hasImage: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface AnnouncementRequest {
  slug: string;
  titleEn: string | null;
  titleAr: string | null;
  bodyEn: string | null;
  bodyAr: string | null;
  featureKey: string | null;
  sortOrder: number;
  published: boolean;
}

/**
 * The portal's half of announcements. The shops read a different, anonymous endpoint
 * (`/api/public/announcements`) — these paths all require the admin token.
 */
@Injectable({ providedIn: 'root' })
export class AnnouncementService {

  private apiUrl = `${environment.apiUrl}/api/announcements`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<Announcement[]> {
    return this.http.get<Announcement[]>(this.apiUrl);
  }

  create(body: AnnouncementRequest): Observable<Announcement> {
    return this.http.post<Announcement>(this.apiUrl, body);
  }

  update(id: number, body: AnnouncementRequest): Observable<Announcement> {
    return this.http.put<Announcement>(`${this.apiUrl}/${id}`, body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /** Multipart, because the bytes go straight into the row. 512 KB cap, PNG or JPEG. */
  uploadImage(id: number, file: File): Observable<Announcement> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<Announcement>(`${this.apiUrl}/${id}/image`, form);
  }

  removeImage(id: number): Observable<Announcement> {
    return this.http.delete<Announcement>(`${this.apiUrl}/${id}/image`);
  }

  // ── AI ──────────────────────────────────────────────────────────────────────
  // These two cost money each time. The portal shows what is left before either button is
  // pressed, rather than explaining a 429 afterwards.

  aiBudget(): Observable<{ text: number; images: number; textPerDay: number; imagesPerDay: number }> {
    return this.http.get<{ text: number; images: number; textPerDay: number; imagesPerDay: number }>(
      `${this.apiUrl}/ai/budget`);
  }

  /** A DRAFT for the form. Nothing is saved and nothing is published by this call. */
  aiDraft(intent: string, featureKey: string | null): Observable<{
    titleEn: string; titleAr: string; bodyEn: string; bodyAr: string;
  }> {
    return this.http.post<{ titleEn: string; titleAr: string; bodyEn: string; bodyAr: string }>(
      `${this.apiUrl}/ai/draft`, { intent, featureKey });
  }

  /**
   * Draws a picture and attaches it to an announcement that already exists.
   *
   * <p>Attached server-side rather than returned, because the alternative is shipping a
   * megabyte of base64 to the browser so it can post the same bytes straight back.
   */
  aiImage(id: number, brief: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(`${this.apiUrl}/ai/${id}/image`, { brief });
  }

  /**
   * Where the picture is served from. The PUBLIC path, because that is the one the shops use —
   * previewing exactly what they will see is the point of the preview.
   */
  imageUrl(slug: string, cacheBust?: string | null): string {
    const base = `${environment.apiUrl}/api/public/announcements/${encodeURIComponent(slug)}/image`;
    return cacheBust ? `${base}?v=${encodeURIComponent(cacheBust)}` : base;
  }
}

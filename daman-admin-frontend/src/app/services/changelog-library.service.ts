import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** Carries both languages so the shipped app can show release notes in
 *  whichever language the client has selected, not just whatever the admin
 *  happened to type first. */
export interface ChangelogEntry {
  id: string;
  en: string;
  ar: string;
  /**
   * Whether this bullet has already gone out in a released version. Owned by
   * the server — it's set when a version is saved that includes the entry, and
   * a save that sends it back unchanged (or omits it) never clears it. Absent
   * on entries written before the flag existed.
   */
  deployed?: boolean;
}

/**
 * Reads/writes the reusable changelog bullet library backing the Versions
 * page's "Select Changelog" picker — backed by the admin backend's
 * changelog_entries table (see ChangelogEntry.java / ChangelogLibraryService.java),
 * not a file, so it works the same from the hosted admin backend as it does
 * locally.
 */
@Injectable({ providedIn: 'root' })
export class ChangelogLibraryService {
  private apiUrl = `${environment.apiUrl}/api/changelog-library`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<ChangelogEntry[]> {
    return this.http.get<ChangelogEntry[]>(this.apiUrl);
  }

  saveAll(entries: ChangelogEntry[]): Observable<ChangelogEntry[]> {
    return this.http.put<ChangelogEntry[]>(this.apiUrl, entries);
  }
}

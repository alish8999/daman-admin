import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface AppVersion {
  id: number;
  versionNumber: string;
  releaseDate: string;
  changelogText: string;
  createdAt: string;
}

export interface AppVersionRequest {
  versionNumber: string;
  releaseDate: string;
  changelogText: string;
  /**
   * Library entries this changelog was composed from. Saving the version marks
   * them released, so they stop appearing as new in the pickers. Optional —
   * a hand-edited changelog with no picker selection simply marks nothing.
   */
  changelogEntryIds?: string[];
}

@Injectable({ providedIn: 'root' })
export class AppVersionService {
  private apiUrl = `${environment.apiUrl}/api/versions`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<AppVersion[]> {
    return this.http.get<AppVersion[]>(this.apiUrl);
  }

  create(req: AppVersionRequest): Observable<AppVersion> {
    return this.http.post<AppVersion>(this.apiUrl, req);
  }

  update(id: number, req: AppVersionRequest): Observable<AppVersion> {
    return this.http.put<AppVersion>(`${this.apiUrl}/${id}`, req);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

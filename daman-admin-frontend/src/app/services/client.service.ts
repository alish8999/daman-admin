import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ClientConfig, ClientConfigExport, BuildStatus, BuildLogEntry } from '../models/client-config.model';

@Injectable({ providedIn: 'root' })
export class ClientService {
  private apiUrl = `${environment.apiUrl}/api/clients`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<ClientConfig[]> {
    return this.http.get<ClientConfig[]>(this.apiUrl);
  }

  getOne(clientCode: string): Observable<ClientConfig> {
    return this.http.get<ClientConfig>(`${this.apiUrl}/${clientCode}`);
  }

  create(data: ClientConfig): Observable<ClientConfig> {
    return this.http.post<ClientConfig>(this.apiUrl, data);
  }

  update(clientCode: string, data: ClientConfig): Observable<ClientConfig> {
    return this.http.put<ClientConfig>(`${this.apiUrl}/${clientCode}`, data);
  }

  delete(clientCode: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${clientCode}`);
  }

  exportConfig(clientCode: string): Observable<ClientConfigExport> {
    return this.http.get<ClientConfigExport>(`${this.apiUrl}/${clientCode}/export`);
  }

  triggerBuild(clientCode: string, platform: string = 'win', version: string = ''): Observable<BuildStatus> {
    return this.http.post<BuildStatus>(
      `${this.apiUrl}/${clientCode}/build?platform=${platform}&version=${encodeURIComponent(version)}`, {});
  }

  getBuildStatus(clientCode: string): Observable<BuildStatus> {
    return this.http.get<BuildStatus>(`${this.apiUrl}/${clientCode}/build/status`);
  }

  getBuildDownloadUrl(clientCode: string): string {
    return `${this.apiUrl}/${clientCode}/build/download`;
  }

  getBuildHistory(clientCode: string): Observable<BuildLogEntry[]> {
    return this.http.get<BuildLogEntry[]>(`${this.apiUrl}/${clientCode}/build/history`);
  }

  prepareDevConfig(clientCode: string): Observable<{ backendPath: string; frontendPath: string }> {
    return this.http.post<{ backendPath: string; frontendPath: string }>(`${this.apiUrl}/${clientCode}/prepare-config`, {});
  }
}

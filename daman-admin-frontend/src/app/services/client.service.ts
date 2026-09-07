import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ClientConfig, ClientConfigExport, BuildStatus, BuildLogEntry } from '../models/client-config.model';

export interface DevRunResult {
  clientCode: string;
  mode: 'per-client' | 'generic';
  machineId: string;
  dbPath: string;
  dbCopied: boolean;
  licensePath: string;
  notes: string[];
}

export interface DevCurrent {
  clientCode: string | null;
  mode: 'per-client' | 'generic' | null;
  version: string | null;
}

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

  openOutputFolder(clientCode: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/${clientCode}/build/open-folder`, {});
  }

  // ── Generic build — one neutral installer, no client identity baked in.
  //    Local equivalent of the generic-installer CI workflow.

  triggerGenericBuild(platform: string = 'win', version: string = ''): Observable<BuildStatus> {
    return this.http.post<BuildStatus>(
      `${this.apiUrl}/generic-build?platform=${platform}&version=${encodeURIComponent(version)}`, {});
  }

  getGenericBuildStatus(): Observable<BuildStatus> {
    return this.http.get<BuildStatus>(`${this.apiUrl}/generic-build/status`);
  }

  getGenericBuildHistory(): Observable<BuildLogEntry[]> {
    return this.http.get<BuildLogEntry[]>(`${this.apiUrl}/generic-build/history`);
  }

  getGenericBuildDownloadUrl(): string {
    return `${this.apiUrl}/generic-build/download`;
  }

  openGenericOutputFolder(): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/generic-build/open-folder`, {});
  }

  prepareDevConfig(clientCode: string): Observable<{ backendPath: string; frontendPath: string }> {
    return this.http.post<{ backendPath: string; frontendPath: string }>(`${this.apiUrl}/${clientCode}/prepare-config`, {});
  }

  // ── Dev "Run as this client" workflow (Task 4/5) ─────────────────────────
  // Safe only because the admin backend runs locally on the developer's machine.

  devRun(clientCode: string, body: { mode: 'per-client' | 'generic'; dbFile?: string }): Observable<DevRunResult> {
    return this.http.post<DevRunResult>(`${this.apiUrl}/${clientCode}/dev-run`, body);
  }

  devCurrent(): Observable<DevCurrent> {
    return this.http.get<DevCurrent>(`${this.apiUrl}/dev-current`);
  }

  devReset(): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/dev-reset`, {});
  }

  setDevMachineId(machineId: string): Observable<void> {
    return this.http.put<void>(`${this.apiUrl}/dev-machine-id`, { machineId });
  }
}

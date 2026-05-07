import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface License {
  id: number;
  clientCode: string;
  machineId: string;
  licenseKey: string;
  status: string;
  clientName: string;
  expiresAt: string | null;
  deviceInfo: string;
  activatedAt: string;
  revokedAt: string | null;
}

export interface GenerateLicenseRequest {
  machineId: string;
  clientCode: string;
  expiresAt?: string;
}

export interface GenerateLicenseResponse {
  licenseKey: string;
  clientName: string;
  machineId: string;
}

@Injectable({ providedIn: 'root' })
export class LicenseService {
  private apiUrl = `${environment.apiUrl}/api/licenses`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<License[]> {
    return this.http.get<License[]>(this.apiUrl);
  }

  getByClient(clientCode: string): Observable<License[]> {
    return this.http.get<License[]>(`${this.apiUrl}/client/${clientCode}`);
  }

  generate(req: GenerateLicenseRequest): Observable<GenerateLicenseResponse> {
    return this.http.post<GenerateLicenseResponse>(`${this.apiUrl}/generate`, req);
  }

  revoke(id: number): Observable<any> {
    return this.http.post(`${this.apiUrl}/${id}/revoke`, {});
  }

  revokeByClient(clientCode: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/client/${clientCode}/revoke`, {});
  }

  renewByClient(clientCode: string, expiresAt?: string): Observable<any> {
    return this.http.put(`${this.apiUrl}/client/${clientCode}/renew`, { expiresAt: expiresAt ?? '' });
  }

  getPublicKey(): Observable<{ publicKey: string }> {
    return this.http.get<{ publicKey: string }>(`${this.apiUrl}/public-key`);
  }
}

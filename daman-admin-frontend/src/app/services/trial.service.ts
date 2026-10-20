import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export type TrialState = 'ACTIVE' | 'EXPIRED' | 'CONVERTED';

export interface TrialRegistration {
  id: number;
  clientCode: string;
  shopName: string;
  phone: string | null;
  address: string | null;
  storeType: string | null;
  hasTables: boolean | null;
  baseCurrency: string | null;
  registeredAt: string;
  expiresAt: string;
  convertedAt: string | null;
  /** Computed server-side from registeredAt/expiresAt only - never desktop-reported. */
  dayNumber: number;
  totalDays: number;
  state: TrialState;
}

@Injectable({ providedIn: 'root' })
export class TrialService {
  private apiUrl = `${environment.apiUrl}/api/trials`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<TrialRegistration[]> {
    return this.http.get<TrialRegistration[]>(this.apiUrl);
  }
}

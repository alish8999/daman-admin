import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Billing, BillingRequest } from '../models/billing.model';

@Injectable({ providedIn: 'root' })
export class BillingService {
  private apiUrl = `${environment.apiUrl}/api/billings`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<Billing[]> {
    return this.http.get<Billing[]>(this.apiUrl);
  }

  getByClient(clientCode: string): Observable<Billing[]> {
    return this.http.get<Billing[]>(`${this.apiUrl}/client/${clientCode}`);
  }

  create(clientCode: string, req: BillingRequest): Observable<Billing> {
    return this.http.post<Billing>(`${this.apiUrl}/client/${clientCode}`, req);
  }

  update(id: number, req: BillingRequest): Observable<Billing> {
    return this.http.put<Billing>(`${this.apiUrl}/${id}`, req);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

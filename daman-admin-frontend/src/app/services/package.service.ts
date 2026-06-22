import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { PackageDefinitions } from '../models/package-definition.model';

@Injectable({ providedIn: 'root' })
export class PackageService {
  private apiUrl = `${environment.apiUrl}/api/packages`;

  constructor(private http: HttpClient) {}

  getDefinitions(): Observable<PackageDefinitions> {
    return this.http.get<PackageDefinitions>(this.apiUrl);
  }

  saveDefinitions(defs: PackageDefinitions): Observable<PackageDefinitions> {
    return this.http.put<PackageDefinitions>(this.apiUrl, defs);
  }
}

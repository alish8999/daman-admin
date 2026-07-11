import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly storageKey = 'admin-auth-token';
  private readonly apiUrl = `${environment.apiUrl}/api/auth`;

  constructor(private http: HttpClient, private router: Router) {}

  login(username: string, password: string): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.apiUrl}/login`, { username, password }).pipe(
      tap(res => localStorage.setItem(this.storageKey, res.token))
    );
  }

  logout(): void {
    const hadToken = !!this.getToken();
    if (hadToken) {
      this.http.post(`${this.apiUrl}/logout`, {}).subscribe({ next: () => {}, error: () => {} });
    }
    localStorage.removeItem(this.storageKey);
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return localStorage.getItem(this.storageKey);
  }

  isAuthenticated(): boolean {
    return !!this.getToken();
  }
}

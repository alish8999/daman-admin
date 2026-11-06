import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** The newest report from one client. Absent entirely for a client that has never reported. */
export interface LatestHeartbeat {
  clientCode: string;
  /** Server time the report arrived — never a timestamp the client chose. */
  lastSeenAt: string;
  /** The day that till last rang up a sale, or null if it never has. */
  lastSaleOn: string | null;
  appVersion: string | null;
}

/** One stored report, for the client detail view. */
export interface HeartbeatRow extends LatestHeartbeat {
  id: number;
  machineId: string;
  /** All-time sale count on that installation — cumulative, so deltas are a subtraction. */
  totalSales: number | null;
  activeUserCount: number | null;
}

/**
 * What installed clients have reported about themselves.
 *
 * Read-only from the portal's side: the write path is the anonymous endpoint the clients
 * themselves post to, which lives on the same controller but under /api/public/.
 */
@Injectable({ providedIn: 'root' })
export class ClientHeartbeatService {

  constructor(private http: HttpClient) {}

  /** Newest report per client, in one request — the Clients list renders a row per client. */
  latest(): Observable<LatestHeartbeat[]> {
    return this.http.get<LatestHeartbeat[]>(`${environment.apiUrl}/api/client-heartbeats/latest`);
  }

  history(clientCode: string): Observable<HeartbeatRow[]> {
    return this.http.get<HeartbeatRow[]>(
      `${environment.apiUrl}/api/client-heartbeats/${encodeURIComponent(clientCode)}`);
  }
}

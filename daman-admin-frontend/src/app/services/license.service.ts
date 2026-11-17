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
  label: string | null;
  expiresAt: string | null;
  deviceInfo: string;
  activatedAt: string;
  revokedAt: string | null;
  renewedAt: string | null;
  payloadVersion?: number;
  /** True iff this row was v1→v2 re-issued and still has a stored previous key (can be reverted). */
  reissued?: boolean;
  /**
   * ACTIVE **and** not past its expiry — not the same as `status === 'ACTIVE'`. On the live
   * fleet 43 of the 101 ACTIVE licences are expired, so this is what "active licences" counts.
   */
  live?: boolean;
  /**
   * What this licence actually grants, and which flags deviate from the client's default.
   *
   * <p><b>Null on the whole-fleet listing</b> (`getAll`), which would otherwise resolve
   * entitlements once per row across 105 licences. Populated by `getByClient`. Null means
   * "not loaded", **never** "no features" — treating it as empty would show a fully-licensed
   * shop as having nothing.
   */
  effectiveFeatures?: Record<string, boolean> | null;
  featureOverride?: Record<string, boolean> | null;
}

/** One flag whose stored key disagrees with what the licence should grant. */
export interface FlagChange {
  key: string;
  inKey: boolean;
  resolved: boolean;
}

/** One licence whose signed key is out of step with its client's features. */
export interface DriftRow {
  licenceId: number;
  clientCode: string;
  label: string | null;
  machineId: string;
  changes: FlagChange[];
}

/** What happened to one licence during a re-mint. `skippedReason` is null iff `minted`. */
export interface RemintOutcome {
  licenceId: number;
  clientCode: string | null;
  machineId: string | null;
  minted: boolean;
  skippedReason: string | null;
}

/** The response to changing one licence's feature override. */
export interface LicenceFeatureUpdate {
  effectiveFeatures: Record<string, boolean>;
  featureOverride: Record<string, boolean>;
  reminted: boolean;
  /** The licence's CURRENT key — the re-mint replaced it. Merge it, or a later "Download
   *  .dat" hands the shop the superseded key. */
  licenseKey: string;
  skippedReason: string | null;
}

export interface GenerateLicenseRequest {
  machineId: string;
  clientCode: string;
  label?: string;
  expiresAt?: string;
}

export interface GenerateLicenseResponse {
  licenseKey: string;
  clientName: string;
  machineId: string;
  label?: string;
}

export interface ReissuePreviewRow {
  clientCode: string;
  clientName: string;
  machineId: string;
  currentVersion: number;
  expiresAt: string | null;
  clientConfigPresent: boolean;
}

export interface ReissuePreview {
  eligible: ReissuePreviewRow[];
  skipped: ReissuePreviewRow[];
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

  /** Renew a specific license by its ID (preferred — unambiguous with multiple licenses per client). */
  renewById(id: number, expiresAt?: string): Observable<any> {
    return this.http.put(`${this.apiUrl}/${id}/renew`, { expiresAt: expiresAt ?? '' });
  }

  /** Legacy: renew most-recent active license by clientCode. */
  renewByClient(clientCode: string, expiresAt?: string): Observable<any> {
    return this.http.put(`${this.apiUrl}/client/${clientCode}/renew`, { expiresAt: expiresAt ?? '' });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /**
   * Change how ONE licence differs from its client's default feature set.
   *
   * <p>A merge patch: `true`/`false` starts or changes an override, `null` stops overriding that
   * key, and `{}` clears the override entirely (back to "inherit everything"). Saves, then
   * re-mints that licence's key — `reminted: false` with a `skippedReason` means the override was
   * stored but the key did not go out (expired, revoked, a v1 key, or a drift that needs the
   * reconcile first).
   */
  setFeatures(id: number, patch: Record<string, boolean | null>): Observable<LicenceFeatureUpdate> {
    return this.http.put<LicenceFeatureUpdate>(`${this.apiUrl}/${id}/features`, patch);
  }

  /** Every live licence whose stored key disagrees with its client's features. Reads only. */
  featuresDrift(): Observable<DriftRow[]> {
    return this.http.get<DriftRow[]>(`${this.apiUrl}/features-drift`);
  }

  /**
   * Correct the drift: re-mint exactly the rows the preview lists.
   *
   * <p>**The only call that may take a feature away from a shop currently running it.** Always
   * show the preview first.
   */
  reconcileFeaturesDrift(): Observable<RemintOutcome[]> {
    return this.http.post<RemintOutcome[]>(`${this.apiUrl}/features-drift/reconcile`, {});
  }

  getPublicKey(): Observable<{ publicKey: string }> {
    return this.http.get<{ publicKey: string }>(`${this.apiUrl}/public-key`);
  }

  reissuePreview(): Observable<ReissuePreview> {
    return this.http.get<ReissuePreview>(`${this.apiUrl}/reissue-v2/preview`);
  }

  reissueV2Bundle(): Observable<Blob> {
    return this.http.post(`${this.apiUrl}/reissue-v2`, {}, { responseType: 'blob' });
  }

  /** Restore a re-issued licence's previous key (undo a v1→v2 re-issue). */
  revertReissue(licenseId: number): Observable<{ clientCode: string; machineId: string; restoredKey: string }> {
    return this.http.post<{ clientCode: string; machineId: string; restoredKey: string }>(
      `${this.apiUrl}/${licenseId}/revert-reissue`, {});
  }
}

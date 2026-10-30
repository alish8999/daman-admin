import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';

interface CatalogFeature {
  key: string;
  price: number | null;
  contactUs: boolean;
  bundledWith: string | null;
}

interface CatalogResponse {
  currency: string;
  features: CatalogFeature[];
}

/**
 * Add-on prices, read from the backend's `FeatureCatalog` constant.
 *
 * <p>Prices used to live in `feature-catalog.ts`'s own `price` field, which made it one of three
 * disagreeing price lists (the others being the POS upsell modal's markup and the POS features
 * page's card comments). That field is gone; this service is where a price comes from now.
 *
 * <p><b>Deliberately no offline fallback.</b> The POS has one, because a shop can be off the
 * internet for days and must still show a price. The admin portal is a hosted web app that cannot
 * do anything at all without its API, so a fallback here would be dead code that only served to
 * hide a failure. If the fetch fails, prices read as unknown and the screens show their normal
 * error state — never a misleading `$0`.
 *
 * <p>It reads the SESSION-GATED `/api/feature-catalog`, not the anonymous `/api/public/...` twin.
 * The portal already has a session, and keeping the public surface to one endpoint per purpose is
 * what stops the public one quietly acquiring an admin-only field later.
 */
@Injectable({ providedIn: 'root' })
export class FeaturePriceService {

  private byKey = new Map<string, CatalogFeature>();
  private loaded = false;

  constructor(private http: HttpClient) {}

  /** Resolves once the catalog is in hand, or immediately if it already is. Never rejects. */
  async load(): Promise<void> {
    if (this.loaded) return;
    try {
      const res = await firstValueFrom(
        this.http.get<CatalogResponse>(`${environment.apiUrl}/api/feature-catalog`));
      if (!res || !Array.isArray(res.features)) return;
      const next = new Map<string, CatalogFeature>();
      for (const f of res.features) {
        if (f && typeof f.key === 'string') next.set(f.key, f);
      }
      this.byKey = next;
      this.loaded = true;
    } catch {
      // Leave the service empty rather than caching the failure, so a later call retries.
    }
  }

  get isLoaded(): boolean { return this.loaded; }

  /** USD price, or null when unknown, not yet loaded, or priced on enquiry. */
  priceOf(key: string): number | null {
    const f = this.byKey.get(key);
    if (!f || f.contactUs) return null;
    return typeof f.price === 'number' ? f.price : null;
  }

  isContactUs(key: string): boolean {
    return this.byKey.get(key)?.contactUs === true;
  }

  /** True for a key that costs real money — i.e. a paid add-on, not a free or bundled one. */
  isPaid(key: string): boolean {
    const price = this.priceOf(key);
    return price !== null && price > 0;
  }

  /**
   * Total add-on value for a set of enabled flags.
   *
   * <p>This sums the catalog's prices as they stand, which preserves the bundle semantics the old
   * `addonValue()` had: `manufacturing` is priced at its MARGINAL cost on top of `productRecipes`,
   * so a client holding both totals the $50 bundle rather than being charged $25 twice over.
   */
  addonValue(enabled: Record<string, boolean | undefined>): number {
    let sum = 0;
    this.byKey.forEach((f, key) => {
      if (enabled[key] && typeof f.price === 'number') sum += f.price;
    });
    return sum;
  }
}

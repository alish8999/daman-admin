import { Component, HostListener, inject } from '@angular/core';
import { Router } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Hosts the fleet page inside the portal.
 *
 * <p>The page itself is the deploy dashboard's own HTML/JS/CSS, copied verbatim into the build by
 * two asset entries in angular.json. It is not rewritten as Angular components: it is
 * framework-free on purpose and carries its own tests (`store-row-core`, `api-base-core`), which
 * is exactly what survives a move intact.
 *
 * <p><b>An iframe rather than injected markup.</b> The page brings its own stylesheet, written
 * against no framework; letting it loose in a document styled by Bootstrap would wreck both.
 *
 * <p><b>Why this component exists at all</b>, rather than linking straight at the static file:
 * `authGuard` is a route guard and cannot protect a bare asset. The file is public either way, and
 * inert without a session — but it is also the one origin the local agent trusts, so it gets the
 * same gate as every other page in the portal.
 */
@Component({
  selector: 'app-fleet',
  standalone: true,
  template: `
    <iframe
      class="fleet-frame"
      [src]="src"
      title="Hosted store fleet"
      referrerpolicy="same-origin"></iframe>
  `,
  styles: [`
    :host { display: block; height: 100%; }
    .fleet-frame { width: 100%; height: calc(100vh - 56px); border: 0; display: block; }
  `],
})
export class FleetComponent {
  private readonly router = inject(Router);

  /**
   * Same-origin by construction — a relative path, never a URL built from anything a caller
   * supplied — so the page can read the portal session from localStorage and the sanitizer has
   * nothing variable to trust.
   */
  readonly src: SafeResourceUrl =
    inject(DomSanitizer).bypassSecurityTrustResourceUrl('fleet-page/index.html');

  /**
   * The page posts here when a call comes back 401, and the portal owns what happens next.
   *
   * Without this the fleet would poll a dead session for ever, showing an empty table that reads
   * as "no stores" rather than "log in again" — the failure the design called out by name.
   */
  @HostListener('window:message', ['$event'])
  onMessage(event: MessageEvent): void {
    if (event.origin !== window.location.origin) return;   // only our own iframe
    if (event.data === 'fleet:unauthorised') {
      // Exactly what authInterceptor does for the rest of the portal. Navigating alone left the
      // dead token in storage, so authGuard waved the user straight back into /fleet for another
      // silent 401 — and dropped them on a bare login form with no explanation, because
      // LoginComponent reads `expired` to say "Your session has expired."
      localStorage.removeItem('admin-auth-token');
      this.router.navigate(['/login'], { queryParams: { expired: 1 } });
    }
  }
}

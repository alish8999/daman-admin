import { Component, HostListener } from '@angular/core';
import { Router, RouterOutlet, RouterLink, RouterLinkActive, NavigationEnd } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { TranslationService, Language } from './services/translation.service';
import { TranslatePipe } from './pipes/translate.pipe';
import { AuthService } from './services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AsyncPipe, TranslatePipe],
  templateUrl: './app.component.html'
})
export class AppComponent {
  showShell = true;
  /** Phone layout: the sidebar is an off-canvas drawer, toggled from the top bar. */
  mobileMenuOpen = false;

  constructor(
    public translationService: TranslationService,
    public authService: AuthService,
    private router: Router
  ) {
    this.showShell = this.router.url !== '/login';
    this.router.events.subscribe(event => {
      if (event instanceof NavigationEnd) {
        this.mobileMenuOpen = false;
        this.showShell = event.urlAfterRedirects !== '/login';
      }
    });
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen = !this.mobileMenuOpen;
  }

  @HostListener('document:keydown.escape')
  closeMobileMenu(): void {
    this.mobileMenuOpen = false;
  }

  setLanguage(lang: Language): void {
    this.translationService.setLanguage(lang);
  }

  isActiveLang(lang: Language): boolean {
    return this.translationService.getCurrentLanguage() === lang;
  }

  logout(): void {
    this.authService.logout();
  }
}

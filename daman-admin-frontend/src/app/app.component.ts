import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { TranslationService, Language } from './services/translation.service';
import { TranslatePipe } from './pipes/translate.pipe';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AsyncPipe, TranslatePipe],
  templateUrl: './app.component.html'
})
export class AppComponent {
  constructor(public translationService: TranslationService) {}

  setLanguage(lang: Language): void {
    this.translationService.setLanguage(lang);
  }

  isActiveLang(lang: Language): boolean {
    return this.translationService.getCurrentLanguage() === lang;
  }
}

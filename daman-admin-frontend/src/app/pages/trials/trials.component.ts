import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TrialService, TrialRegistration } from '../../services/trial.service';
import { TranslatePipe } from '../../pipes/translate.pipe';

@Component({
  selector: 'app-trials',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './trials.component.html',
  styleUrl: './trials.component.css'
})
export class TrialsComponent implements OnInit {
  trials: TrialRegistration[] = [];
  loading = true;
  error = false;

  constructor(private trialService: TrialService) {}

  ngOnInit(): void {
    this.trialService.getAll().subscribe({
      next: rows => { this.trials = rows; this.loading = false; },
      error: () => { this.error = true; this.loading = false; }
    });
  }

  count(state: string): number {
    return this.trials.filter(t => t.state === state).length;
  }

  /**
   * WhatsApp deep link for a trial's phone number.
   *
   * Same shape as the Clients page's own helper, deliberately duplicated rather than shared:
   * it is one line, and the two pages have no other contact logic in common. wa.me wants digits
   * only — no plus, no spaces, no dashes — and silently fails on anything else, which is the
   * kind of thing that looks like "the button is broken" rather than "the number was formatted".
   */
  whatsappUrl(phone: string): string {
    return `https://wa.me/${phone.replace(/[^0-9]/g, '')}`;
  }

  /**
   * Whether a number is worth offering a button for.
   *
   * A trial is self-registered, so the phone field is whatever the prospect typed. Fewer than
   * a handful of digits cannot be a real number, and a dead wa.me link reads as a broken page.
   */
  canMessage(phone: string | null | undefined): boolean {
    return !!phone && phone.replace(/[^0-9]/g, '').length >= 7;
  }
}

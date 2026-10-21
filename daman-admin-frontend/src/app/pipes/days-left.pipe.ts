import { Pipe, PipeTransform } from '@angular/core';
import { TranslationService } from '../services/translation.service';

/**
 * Turns a licence/support day count into readable text.
 *
 * <p>These were rendered as a bare <code>{{ n }}d</code> everywhere, which is not English or
 * Arabic - it is a unit suffix that happens to be an English initial, and it told an Arabic
 * reader nothing. "0d" in particular read as a licence with no time left rather than one
 * expiring today.
 *
 * <p>ARABIC NEEDS FOUR FORMS, not two. Picking between a singular and a plural the way English
 * does produces wrong grammar for half the range:
 * <ul>
 *   <li>1  → يوم واحد        (singular)</li>
 *   <li>2  → يومان           (DUAL - Arabic has a form English does not)</li>
 *   <li>3-10 → ٣ أيام        (plural of paucity, noun plural)</li>
 *   <li>11+  → ١١ يوماً       (noun returns to the singular, accusative)</li>
 * </ul>
 * English collapses all four onto two keys, which is why the keys are named by form rather
 * than by number.
 *
 * <p>Impure, like TranslatePipe: it has to re-run when the language changes.
 */
@Pipe({ name: 'daysLeft', standalone: true, pure: false })
export class DaysLeftPipe implements PipeTransform {
  constructor(private translationService: TranslationService) {}

  transform(days: number | null | undefined): string {
    if (days === null || days === undefined || !Number.isFinite(days)) {
      return '—';
    }
    const n = Math.trunc(days);
    if (n < 0) {
      const ago = Math.abs(n);
      return this.translationService.instant(DaysLeftPipe.formKey(ago, 'expired'), { n: ago });
    }
    if (n === 0) {
      return this.translationService.instant('expiryToday');
    }
    return this.translationService.instant(DaysLeftPipe.formKey(n, 'left'), { n });
  }

  /** The Arabic form this count takes. English maps several of them onto the same string. */
  static formKey(n: number, suffix: 'left' | 'expired'): string {
    if (n === 1) return `expiryOne_${suffix}`;
    if (n === 2) return `expiryTwo_${suffix}`;
    // 3-10 take the plural; 11 and up return to the singular noun. The rule is on the LAST
    // two digits, so 103 is "few" and 111 is "many" - not simply "n <= 10".
    const lastTwo = n % 100;
    return lastTwo >= 3 && lastTwo <= 10 ? `expiryFew_${suffix}` : `expiryMany_${suffix}`;
  }
}

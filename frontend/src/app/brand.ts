import { Component, Input } from '@angular/core';

/**
 * Tô Dentro brand mark.
 * - `seal` (principal): the name followed by the confirmation check.
 * - `button` (destaque): the lime capsule that repeats the presence button.
 * The mark is decorative text; give the surrounding link or heading its accessible name.
 */
@Component({
  selector: 'app-brand',
  standalone: true,
  template: `
    @if (variant === 'button') {
      <span class="brand-capsule" aria-hidden="true"
        ><span class="brand-check"
          ><svg viewBox="0 0 24 24"><path d="M5 12.5l4.2 4.2L19 7" /></svg></span
        ><span class="brand-word">tô dentro</span></span
      >
    } @else {
      <span class="brand-seal" aria-hidden="true"
        ><span class="brand-word">tô dentro</span
        ><span class="brand-check"
          ><svg viewBox="0 0 24 24"><path d="M5 12.5l4.2 4.2L19 7" /></svg></span
      ></span>
    }
  `,
})
export class Brand {
  @Input() variant: 'seal' | 'button' = 'seal';
}

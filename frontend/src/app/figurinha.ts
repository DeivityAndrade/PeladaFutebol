import { Component, input } from '@angular/core';
import { Icon } from './icon';
import { Brand } from './brand';
import { FigurinhaData, achievementNames } from './career-models';

@Component({
  selector: 'app-figurinha',
  standalone: true,
  imports: [Icon, Brand],
  template: ` <article
    class="figurinha"
    [class.carimbada]="card().frame === 'TEN_APPEARANCES'"
    [attr.aria-label]="'Figurinha de ' + card().name"
  >
    <div class="card-header">
      <app-brand /><span>{{ clubName() }}</span>
    </div>
    <div class="card-portrait">
      @if (card().photoUrl) {
        <img class="card-photo" [src]="card().photoUrl" alt="" />
      } @else {
        <span class="card-initials" aria-hidden="true">{{ initials() }}</span>
      }
    </div>
    <div class="card-name">
      <h3>{{ card().name }}</h3>
      <p>{{ card().title ? names[card().title!] : 'O futebol aproxima.' }}</p>
    </div>
    @if (card().badges.length) {
      <ul class="card-badges" aria-label="Selos escolhidos">
        @for (badge of card().badges; track badge) {
          <li><app-icon name="check" />{{ names[badge] }}</li>
        }
      </ul>
    }
    @if (card().frame === 'TEN_APPEARANCES') {
      <span class="card-stamp">Figurinha carimbada</span>
    }
  </article>`,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
        max-width: 340px;
      }
      .figurinha {
        position: relative;
        overflow: hidden;
        padding: 24px;
        border: 1px solid var(--night-line);
        border-radius: var(--radius-card);
        background: var(--night);
        color: var(--night-ink);
      }
      .figurinha.carimbada {
        border: 3px solid var(--accent);
        padding: 22px;
      }
      .card-header {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      app-brand {
        width: 126px;
      }
      .card-header > span {
        color: var(--night-muted);
        font-size: 14px;
        overflow-wrap: anywhere;
      }
      .card-portrait {
        display: grid;
        place-items: center;
        height: 180px;
        margin: 22px 0 18px;
        border: 1px solid var(--night-line);
        border-radius: 80px 80px 16px 16px;
        background: var(--night-2);
      }
      .card-initials {
        display: grid;
        place-items: center;
        width: 100px;
        height: 100px;
        border: 1px solid var(--night-muted);
        border-radius: 50%;
        font: 700 48px var(--font-display);
        color: var(--accent);
      }
      .card-name h3 {
        margin: 0;
        color: var(--night-ink);
        font: 700 30px/1.1 var(--font-display);
        overflow-wrap: anywhere;
      }
      .card-photo {
        width: 140px;
        height: 140px;
        object-fit: cover;
        border-radius: 50%;
      }
      .card-name p {
        margin: 8px 0 0;
        color: var(--night-muted);
      }
      .card-badges {
        display: flex;
        flex-wrap: wrap;
        list-style: none;
        gap: 8px;
        padding: 0;
        margin: 20px 0 0;
      }
      .card-badges li {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        color: var(--accent);
        font-size: 13px;
      }
      app-icon {
        width: 16px;
        height: 16px;
      }
      .card-stamp {
        display: block;
        margin-top: 18px;
        padding-top: 14px;
        border-top: 1px solid var(--night-line);
        color: var(--accent);
        font-size: 13px;
      }
    `,
  ],
})
export class Figurinha {
  card = input.required<FigurinhaData>();
  clubName = input('');
  names = achievementNames;
  initials() {
    return this.card()
      .name.trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((n) => n[0])
      .join('')
      .toLocaleUpperCase('pt-BR');
  }
}

import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { Detail } from './models';
import { Icon } from './icon';
import { Brand } from './brand';
import { Pitch } from './pitch';

/**
 * Página inicial pública do Tô Dentro.
 * A prévia do topo usa o componente real do campo com os dados da demonstração pública.
 */
@Component({
  selector: 'app-home-page',
  standalone: true,
  imports: [Icon, Brand, Pitch],
  templateUrl: './home-page.html',
})
export class HomePage {
  @Input() signedIn = false;
  @Input() set preview(value: Detail | null) {
    this.detail.set(value);
  }
  @Output() create = new EventEmitter<void>();
  @Output() login = new EventEmitter<void>();
  @Output() demo = new EventEmitter<boolean>();

  detail = signal<Detail | null>(null);
  team = computed(() => this.detail()?.teams[0] || null);
  roster = computed(
    () => this.detail()?.attendees.filter((p) => p.teamId === this.team()?.id) || [],
  );
  capacity = computed(() => {
    const game = this.detail()?.game;
    return game ? game.teamCount * game.teamSize : 0;
  });

  when(iso: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      weekday: 'short',
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: this.detail()?.club.timeZone || 'America/Sao_Paulo',
    }).format(new Date(iso));
  }
}

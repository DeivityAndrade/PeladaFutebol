import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

export interface GroupPlayer {
  playerId: string;
  name: string;
  primaryPosition: string | null;
  secondaryPosition: string | null;
  skillLevel: number | null;
}
export const positions = [
  { value: 'GOALKEEPER', label: 'Goleiro' },
  { value: 'DEFENSE', label: 'Defesa' },
  { value: 'MIDFIELD', label: 'Meio-campo' },
  { value: 'ATTACK', label: 'Ataque' },
  { value: 'VERSATILE', label: 'Polivalente' },
];
export const levels = [
  { value: 1, label: '1 · Iniciante', description: 'Está aprendendo os fundamentos.' },
  {
    value: 2,
    label: '2 · Em desenvolvimento',
    description: 'Conhece o jogo, ainda ganha consistência.',
  },
  { value: 3, label: '3 · Regular', description: 'Joga com frequência e domina os fundamentos.' },
  { value: 4, label: '4 · Avançado', description: 'Tem boa técnica e leitura de jogo.' },
  {
    value: 5,
    label: '5 · Destaque',
    description: 'Tem técnica e desempenho consistentes acima da média do grupo.',
  },
];
export function positionLabel(value: string | null) {
  return positions.find((p) => p.value === value)?.label || 'Sem posição';
}
@Component({
  selector: 'app-group-players',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './group-players.html',
  styleUrl: './group-players.css',
})
export class GroupPlayers implements OnInit {
  clubId = input.required<string>();
  canManage = input(false);
  private api = inject(Api);
  players = signal<GroupPlayer[]>([]);
  loading = signal(true);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  editing = signal<GroupPlayer | null>(null);
  search = '';
  primary = 'VERSATILE';
  secondary = '';
  level = 3;
  positions = positions;
  levels = levels;
  positionLabel = positionLabel;
  filtered() {
    return this.players().filter((p) =>
      p.name.toLocaleLowerCase('pt-BR').includes(this.search.toLocaleLowerCase('pt-BR')),
    );
  }
  async ngOnInit() {
    try {
      this.players.set(await this.api.request<GroupPlayer[]>(`/groups/${this.clubId()}/players`));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
  edit(p: GroupPlayer) {
    this.error.set('');
    this.notice.set('');
    this.primary = p.primaryPosition || 'VERSATILE';
    this.secondary = p.secondaryPosition || '';
    this.level = p.skillLevel || 3;
    this.editing.set(p);
    setTimeout(() => document.getElementById('classification-position')?.focus());
  }
  cancel() {
    const id = this.editing()?.playerId;
    this.editing.set(null);
    setTimeout(() => document.getElementById('classify-' + id)?.focus());
  }
  async save(clear = false) {
    const p = this.editing();
    if (!p || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const updated = await this.api.request<GroupPlayer>(
        `/groups/${this.clubId()}/players/${p.playerId}/classification`,
        'PUT',
        clear
          ? { primaryPosition: null, secondaryPosition: null, skillLevel: null }
          : {
              primaryPosition: this.primary,
              secondaryPosition: this.secondary || null,
              skillLevel: Number(this.level),
            },
      );
      this.players.update((items) =>
        items.map((item) => (item.playerId === updated.playerId ? updated : item)),
      );
      this.notice.set(clear ? 'Classificação removida.' : 'Classificação salva neste grupo.');
      this.cancel();
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
}

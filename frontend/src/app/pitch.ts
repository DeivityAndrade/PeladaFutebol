import { Component, Input, Output, EventEmitter } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Player, Team, formations } from './models';
import { Icon } from './icon';

@Component({
  selector: 'app-pitch',
  standalone: true,
  imports: [FormsModule, Icon],
  template: `
    <div class="pitch-toolbar">
      <div>
        <span class="eyebrow">Esquema</span
        ><strong>{{ team.formation.replaceAll('-', ' – ') }}</strong>
      </div>
      <label class="sr-only" for="formation">Formação</label
      ><select
        id="formation"
        [disabled]="!editable || busy"
        [ngModel]="team.formation"
        (ngModelChange)="changeFormation($event)"
      >
        <option value="2-2">2 – 2 · Quadrado</option>
        <option value="1-2-1">1 – 2 – 1 · Diamante</option>
        <option value="3-1">3 – 1 · Ofensivo</option>
      </select>
    </div>
    <div class="field" aria-label="Campo com cinco posições" [style.--team-color]="team.color">
      <div class="field-boundary">
        <div class="halfway"></div>
        <div class="center-circle"></div>
        <div class="center-dot"></div>
        <div class="area top"></div>
        <div class="area bottom"></div>
        <div class="goal top"></div>
        <div class="goal bottom"></div>
      </div>
      @for (pos of positions; track $index; let slot = $index) {
        <button
          class="field-player"
          [style.left.%]="pos.x"
          [style.top.%]="pos.y"
          [class.empty]="!at(slot)"
          [class.selected]="selecting === slot"
          [class.me]="!!meId && at(slot)?.id === meId"
          [disabled]="!editable || busy"
          [draggable]="editable && !!at(slot)"
          (dragstart)="drag($event, at(slot))"
          (dragover)="$event.preventDefault()"
          (drop)="drop($event, slot)"
          (click)="selecting = slot"
          [attr.aria-label]="
            'Escalar ' + pos.label + (at(slot) ? ': ' + at(slot)!.name : ' — posição vazia')
          "
        >
          <span class="jersey"
            ><svg viewBox="0 0 60 58" aria-hidden="true">
              <path
                d="M17 4L5 11 1 26l12 5 4-9v32h26V22l4 9 12-5-4-15-12-7-5-3c-2 8-14 8-16 0z"
                fill="currentColor"
                stroke="rgba(0,0,0,.16)"
                stroke-width="1.5"
              /></svg
            ><b [style.color]="at(slot) ? numberInk() : '#ffffff'">{{
              at(slot) ? numberFor(at(slot)!) : '+'
            }}</b></span
          >
          <span class="player-label">{{ at(slot) ? shortName(at(slot)!.name) : 'Escolher' }}</span
          ><span class="position-label">{{ pos.label }}</span>
        </button>
      }
    </div>
    <div class="pitch-note">
      <app-icon [name]="editable ? 'edit' : 'lock'" /><span>{{
        editable
          ? 'Toque em uma posição ou arraste um jogador para escalar.'
          : 'Escalação do capitão · somente para consulta'
      }}</span>
    </div>
    @if (selecting !== null && editable) {
      <div class="position-picker">
        <label for="slot-player">{{ positions[selecting].label }} · escolher jogador</label>
        <div class="inline">
          <select
            id="slot-player"
            [ngModel]="at(selecting)?.id || ''"
            (ngModelChange)="choose($event)"
          >
            <option value="">Deixar posição vazia</option>
            @for (p of roster; track p.id) {
              <option [value]="p.id">{{ p.name }}</option>
            }</select
          ><button class="icon-button" aria-label="Fechar seleção" (click)="selecting = null">
            <app-icon name="close" />
          </button>
        </div>
      </div>
    }
    <div class="bench-heading">
      <div>
        <app-icon name="users" />
        <h3>Banco de reservas</h3>
        <span class="count">{{ bench.length }}</span>
      </div>
      <span>Prontos para entrar</span>
    </div>
    <div class="bench" (dragover)="$event.preventDefault()" (drop)="drop($event, null)">
      @for (p of bench; track p.id) {
        <button
          class="bench-player"
          [draggable]="editable"
          (dragstart)="drag($event, p)"
          (click)="benchSelect(p)"
          [disabled]="!editable || busy"
        >
          <span class="avatar" [style.background]="team.color">{{ initials(p.name) }}</span
          ><strong>{{ shortName(p.name) }}</strong
          ><span>Reserva</span>
        </button>
      }
      @if (!bench.length) {
        <p class="muted small">
          Os reservas aparecerão aqui quando houver jogadores fora do campo.
        </p>
      }
    </div>
  `,
  styles: [],
})
export class Pitch {
  @Input({ required: true }) team!: Team;
  @Input() roster: Player[] = [];
  @Input() editable = false;
  @Input() busy = false;
  @Input() meId: string | null = null;
  @Output() save = new EventEmitter<{
    formation: string;
    slots: (string | null)[];
    version: number;
  }>();
  selecting: number | null = null;
  get positions() {
    return formations[this.team.formation];
  }
  get bench() {
    return this.roster.filter((p) => p.slot === null);
  }
  at(slot: number) {
    return this.roster.find((p) => p.slot === slot);
  }
  numberFor(player: Player) {
    return this.roster.findIndex((member) => member.id === player.id) + 1;
  }
  numberInk() {
    const hex = this.team.color.replace('#', '');
    if (!/^(?:[\da-f]{3}|[\da-f]{6})$/i.test(hex)) return '#172b20';
    const full = hex.length === 3 ? [...hex].map((part) => part + part).join('') : hex;
    const rgb = [0, 2, 4].map((index) => parseInt(full.slice(index, index + 2), 16));
    const luminance = rgb.reduce((sum, channel, index) => {
      const value = channel / 255;
      const linear = value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
      return sum + linear * [0.2126, 0.7152, 0.0722][index];
    }, 0);
    return luminance > 0.18 ? '#172b20' : '#ffffff';
  }
  initials(name: string) {
    return name
      .split(' ')
      .slice(0, 2)
      .map((v) => v[0])
      .join('');
  }
  shortName(name: string) {
    const parts = name.split(' ');
    return parts.length > 1 ? parts[0] + ' ' + parts.at(-1)![0] + '.' : name;
  }
  slots() {
    return Array.from({ length: 5 }, (_, i) => this.at(i)?.id || null);
  }
  changeFormation(formation: string) {
    this.save.emit({ formation, slots: this.slots(), version: this.team.version });
  }
  move(id: string | null, target: number | null) {
    if (!this.editable || this.busy) return;
    const slots = this.slots();
    const origin = id ? slots.indexOf(id) : -1;
    const displaced = target !== null ? slots[target] : null;
    if (origin >= 0) slots[origin] = target !== null ? displaced : null;
    if (target !== null) slots[target] = id;
    this.save.emit({ formation: this.team.formation, slots, version: this.team.version });
  }
  choose(id: string) {
    this.move(id || null, this.selecting);
    this.selecting = null;
  }
  drag(event: DragEvent, p: Player | undefined) {
    if (p) event.dataTransfer?.setData('text/plain', p.id);
  }
  drop(event: DragEvent, target: number | null) {
    event.preventDefault();
    const id = event.dataTransfer?.getData('text/plain');
    if (id && this.roster.some((p) => p.id === id)) this.move(id, target);
  }
  benchSelect(p: Player) {
    const target = this.selecting ?? this.slots().findIndex((v) => v === null);
    if (target >= 0) {
      this.move(p.id, target);
      this.selecting = null;
    }
  }
}

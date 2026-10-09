import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

interface Operations {
  available: boolean;
  deliveryAvailable: boolean;
  repliesOnly?: boolean;
  enabled: boolean;
  reminderMinutes: number;
  states: Record<string, number>;
  responses: {
    game_id: string;
    player_id: string;
    title: string;
    starts_at: string;
    time_zone: string;
    name: string;
    response: string;
    attendance: string;
  }[];
}

@Component({
  selector: 'app-whatsapp-operations',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './whatsapp-operations.html',
  styleUrl: './whatsapp-operations.css',
})
export class WhatsAppOperations {
  clubId = input.required<string>();
  clubName = input.required<string>();
  private api = inject(Api);
  private serial = 0;
  state = signal<Operations | null>(null);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  opened = false;
  enabled = false;
  reminderMinutes = 120;
  readonly labels: Record<string, string> = {
    PENDING: 'Agendados',
    CLAIMED: 'Preparando',
    SENDING: 'Enviando',
    ACCEPTED: 'Aceitos pela Meta',
    DELIVERED: 'Entregues',
    READ: 'Lidos',
    FAILED: 'Falharam',
    UNKNOWN: 'Entrega a conferir',
    CANCELLED: 'Cancelados',
  };
  constructor() {
    effect(() => {
      const id = this.clubId();
      untracked(() => {
        ++this.serial;
        this.state.set(null);
        this.error.set('');
        this.notice.set('');
        this.busy.set(false);
        if (this.opened) void this.load(id);
      });
    });
  }
  toggle(event: Event) {
    this.opened = (event.target as HTMLDetailsElement).open;
    if (this.opened && !this.state() && !this.busy()) void this.load();
  }
  async load(id = this.clubId()) {
    const serial = ++this.serial;
    this.busy.set(true);
    this.error.set('');
    try {
      const state = await this.api.request<Operations>(`/whatsapp/groups/${id}/automation`);
      if (serial !== this.serial || id !== this.clubId()) return;
      this.state.set(state);
      this.enabled = state.enabled;
      this.reminderMinutes = state.reminderMinutes;
    } catch (error) {
      if (serial === this.serial)
        this.error.set(
          error instanceof Error ? error.message : 'Não foi possível consultar os avisos.',
        );
    } finally {
      if (serial === this.serial) this.busy.set(false);
    }
  }
  async save() {
    if (this.busy()) return;
    const id = this.clubId(),
      serial = ++this.serial;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const state = await this.api.request<Operations>(`/whatsapp/groups/${id}/automation`, 'PUT', {
        enabled: this.enabled,
        reminderMinutes: Number(this.reminderMinutes),
      });
      if (serial !== this.serial || id !== this.clubId()) return;
      this.state.set(state);
      this.notice.set(
        state.enabled
          ? 'Grupo piloto ativado. Os envios respeitam as autorizações de cada participante.'
          : 'Automação pausada. Envios já submetidos à Meta podem chegar.',
      );
    } catch (error) {
      if (serial === this.serial)
        this.error.set(error instanceof Error ? error.message : 'Não foi possível salvar.');
    } finally {
      if (serial === this.serial) this.busy.set(false);
    }
  }
  counts() {
    return Object.entries(this.state()?.states || {}).map(([key, count]) => ({
      label: this.labels[key] || key,
      count,
    }));
  }
  date(response: Operations['responses'][number]) {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: response.time_zone,
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(response.starts_at));
  }
}

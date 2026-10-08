import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Api } from './api';

interface Preference {
  clubId: string;
  name: string;
  invitations: boolean;
  reminders: boolean;
}
interface Status {
  available: boolean;
  phone: string | null;
  verified: boolean;
  stopped: boolean;
  challengeId: string | null;
  pendingPhone: string | null;
  expiresAt: string | null;
  textVersion: string;
  groups: Preference[];
}
interface Challenge {
  id: string;
  code: string;
  url: string;
  expiresAt: string;
}

@Component({
  selector: 'app-whatsapp-settings',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './whatsapp-settings.html',
  styleUrl: './whatsapp-settings.css',
})
export class WhatsAppSettings implements OnInit {
  private api = inject(Api);
  status = signal<Status | null>(null);
  challenge = signal<Challenge | null>(null);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  disconnecting = signal(false);
  async ngOnInit() {
    await this.run(() => this.load());
  }
  private async load() {
    this.status.set(await this.api.request<Status>('/whatsapp'));
  }
  private async run(action: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      await action();
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
  async refresh() {
    await this.run(async () => {
      await this.load();
      if (!this.status()?.pendingPhone)
        this.notice.set(
          'Ainda não recebemos a mensagem. Envie o código pelo WhatsApp e confira novamente.',
        );
    });
  }
  async begin() {
    await this.run(async () => {
      this.challenge.set(await this.api.request<Challenge>('/whatsapp/verification', 'POST'));
      await this.load();
      this.disconnecting.set(false);
    });
  }
  async confirm() {
    const challengeId = this.status()?.challengeId;
    if (!challengeId) return;
    await this.run(async () => {
      this.status.set(
        await this.api.request<Status>('/whatsapp/verification/confirm', 'POST', { challengeId }),
      );
      this.challenge.set(null);
      this.notice.set('Número vinculado. Escolha abaixo quais avisos deseja autorizar.');
    });
  }
  async disconnect() {
    await this.run(async () => {
      this.status.set(await this.api.request<Status>('/whatsapp', 'DELETE'));
      this.challenge.set(null);
      this.disconnecting.set(false);
      this.notice.set('WhatsApp desconectado. As autorizações de avisos foram canceladas.');
    });
  }
  async save(group: Preference) {
    await this.run(async () => {
      this.status.set(
        await this.api.request<Status>('/whatsapp/groups/' + group.clubId, 'PUT', {
          invitations: group.invitations,
          reminders: group.reminders,
          textVersion: this.status()!.textVersion,
        }),
      );
      this.notice.set(
        'Preferências de ' + group.name + ' salvas. Os envios ainda estão em preparação.',
      );
    });
  }
}

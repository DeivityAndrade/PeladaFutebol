import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  input,
  output,
  signal,
  computed,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { Api, ApiError } from './api';
import { Club } from './models';
import { AudioRequest } from './audio-request';
import { Icon } from './icon';

interface GameOption {
  id: string;
  clubId: string;
  title: string;
  location: string;
  startsAt: string;
  timeZone: string;
}
interface Proposal {
  draft: {
    title: string;
    location: string;
    date: string;
    time: string;
    teamCount: number;
    teamSize: number;
    recurring: boolean;
    recurrenceEndsOn: string | null;
    chargeOccasional: boolean;
    occasionalAmountCents: number | null;
  };
  timeZone: string;
}
interface Action {
  id: string;
  type: string;
  clubId: string;
  label: string;
  summary: string;
  proposal: Proposal | null;
}
interface Reply {
  conversationId: string;
  version: number;
  message: string;
  clubId: string | null;
  games: GameOption[];
  action: Action | null;
  changedGameId: string | null;
}
interface Turn {
  who: 'you' | 'agent';
  text: string;
  games?: GameOption[];
  changedGameId?: string | null;
}

@Component({
  selector: 'app-site-agent',
  standalone: true,
  imports: [FormsModule, DatePipe, CurrencyPipe, AudioRequest, Icon],
  templateUrl: './site-agent.html',
  styleUrl: './site-agent.css',
})
export class SiteAgent implements OnInit, OnDestroy {
  clubs = input.required<Club[]>();
  initialClubId = input('');
  processing = output<boolean>();
  changed = output<string>();
  openGame = output<string>();
  private api = inject(Api);
  private destroyed = false;
  private conversationId: string | null = null;
  private version = 0;
  available = signal<boolean | null>(null);
  audioAvailable = signal(false);
  thinking = signal(false);
  audioBusy = signal(false);
  recording = signal(false);
  busy = computed(() => this.thinking() || this.audioBusy() || this.recording());
  error = signal('');
  turns = signal<Turn[]>([]);
  action = signal<Action | null>(null);
  selectedClubId = '';
  message = '';
  lastFailedText = '';
  async ngOnInit() {
    this.selectedClubId = this.initialClubId();
    try {
      const status = await this.api.request<{ available: boolean; audioAvailable: boolean }>(
        '/assistant/agent',
      );
      if (this.destroyed) return;
      this.available.set(status.available);
      this.audioAvailable.set(status.audioAvailable);
    } catch (e) {
      if (!this.destroyed) {
        this.available.set(false);
        if (!(e instanceof ApiError && e.status === 404)) this.fail(e);
      }
    }
    this.focus();
  }
  ngOnDestroy() {
    this.destroyed = true;
  }
  private focus() {
    requestAnimationFrame(() => {
      if (!this.destroyed) {
        document.getElementById('agent-message')?.focus();
        const log = document.getElementById('agent-thread');
        const latest = log?.querySelector<HTMLElement>('.agent-turn:last-child');
        if (log && latest) log.scrollTop = Math.max(0, latest.offsetTop - 64);
      }
    });
  }
  private status() {
    this.processing.emit(this.thinking() || this.audioBusy());
  }
  capture(value: boolean) {
    this.recording.set(value);
    this.status();
  }
  audioProcessing(value: boolean) {
    this.audioBusy.set(value);
    this.status();
  }
  private fail(e: unknown) {
    this.error.set(e instanceof Error ? e.message : 'Não consegui concluir. Tente novamente.');
  }
  private accept(reply: Reply) {
    this.conversationId = reply.conversationId;
    this.version = reply.version;
    this.selectedClubId = reply.clubId || '';
    this.action.set(reply.action);
    this.turns.update((t) =>
      [
        ...t,
        {
          who: 'agent' as const,
          text: reply.message,
          games: reply.games,
          changedGameId: reply.changedGameId,
        },
      ].slice(-16),
    );
    if (reply.changedGameId) this.changed.emit(reply.changedGameId);
    this.focus();
  }
  async send(text = this.message, audio = false) {
    if (this.thinking() || (!audio && (this.audioBusy() || this.recording())) || !text.trim())
      return;
    this.thinking.set(true);
    this.status();
    this.error.set('');
    this.action.set(null);
    this.lastFailedText = text.trim();
    this.turns.update((t) => [...t, { who: 'you' as const, text: text.trim() }].slice(-16));
    this.message = '';
    try {
      const reply = await this.api.request<Reply>('/assistant/conversations', 'POST', {
        message: text.trim(),
        conversationId: this.conversationId,
        version: this.version,
        selectedClubId: this.selectedClubId || null,
      });
      if (this.destroyed) return;
      this.lastFailedText = '';
      this.accept(reply);
    } catch (e) {
      if (!this.destroyed) this.fail(e);
    } finally {
      if (!this.destroyed) {
        this.thinking.set(false);
        this.status();
      }
    }
  }
  async confirm() {
    const action = this.action();
    if (!action || !this.conversationId || this.busy()) return;
    this.thinking.set(true);
    this.status();
    this.error.set('');
    try {
      const reply = await this.api.request<Reply>(
        '/assistant/conversations/' + this.conversationId + '/confirm',
        'POST',
        { actionId: action.id },
      );
      if (!this.destroyed) this.accept(reply);
    } catch (e) {
      if (!this.destroyed) this.fail(e);
    } finally {
      if (!this.destroyed) {
        this.thinking.set(false);
        this.status();
      }
    }
  }
  changeGroup(id: string) {
    this.selectedClubId = id;
    this.restart(false);
  }
  restart(clearGroup = true) {
    if (this.busy()) return;
    this.conversationId = null;
    this.version = 0;
    this.action.set(null);
    this.turns.set([]);
    this.error.set('');
    this.message = '';
    this.lastFailedText = '';
    if (clearGroup) this.selectedClubId = this.initialClubId();
    this.focus();
  }
  groupName(id: string) {
    return this.clubs().find((c) => c.id === id)?.name || 'Grupo';
  }
  groupLabel(club: Club, index: number) {
    if (
      this.clubs().filter((c) => c.name.toLocaleLowerCase() === club.name.toLocaleLowerCase())
        .length < 2
    )
      return club.name;
    return club.name + ' · ' + (club.description || club.timeZone) + ' · Grupo ' + (index + 1);
  }
  localTime(game: GameOption) {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: game.timeZone,
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(game.startsAt));
  }
  chooseGame(game: GameOption) {
    void this.send(
      'Quero confirmar presença em ' +
        game.title +
        ' na data ' +
        this.localTime(game) +
        ' do grupo ' +
        this.groupName(game.clubId),
    );
  }
}

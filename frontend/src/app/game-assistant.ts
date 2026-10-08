import { Component, OnInit, OnDestroy, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { Api, ApiError } from './api';
import { Club, Detail } from './models';

interface Draft {
  title: string | null;
  location: string | null;
  date: string | null;
  time: string | null;
  teamCount: number | null;
  teamSize: number | null;
  recurring: boolean | null;
  recurrenceEndsOn: string | null;
  chargeOccasional: boolean;
  occasionalAmountCents: number | null;
}
interface Proposal {
  id: string;
  version: number;
  expiresAt: string;
  timeZone: string;
  draft: Draft;
  questions: string[];
  ready: boolean;
}
@Component({
  selector: 'app-game-assistant',
  standalone: true,
  imports: [FormsModule, DatePipe, CurrencyPipe],
  templateUrl: './game-assistant.html',
  styleUrl: './game-assistant.css',
})
export class GameAssistant implements OnInit, OnDestroy {
  club = input.required<Club>();
  created = output<Detail>();
  manual = output<void>();
  processing = output<boolean>();
  private api = inject(Api);
  private destroyed = false;
  available = signal<boolean | null>(null);
  busy = signal(false);
  error = signal('');
  proposal = signal<Proposal | null>(null);
  reviewed = signal(false);
  message = '';
  amount = '';
  draft: Draft | null = null;

  async ngOnInit() {
    try {
      const status = await this.api.request<{ available: boolean }>(
        `/groups/${this.club().id}/assistant`,
      );
      this.available.set(status.available);
    } catch (e) {
      this.available.set(false);
      if (e instanceof ApiError && e.status !== 404) this.fail(e);
    }
    this.focus('assistant-request');
  }
  ngOnDestroy() {
    this.destroyed = true;
  }
  private focus(id: string) {
    requestAnimationFrame(() => {
      if (!this.destroyed) document.getElementById(id)?.focus();
    });
  }
  private setBusy(value: boolean) {
    this.busy.set(value);
    this.processing.emit(value);
  }
  private fail(e: unknown) {
    this.error.set(e instanceof Error ? e.message : 'Não foi possível concluir. Tente novamente.');
    this.focus('assistant-error');
  }
  private accept(p: Proposal) {
    this.proposal.set(p);
    this.draft = { ...p.draft, time: p.draft.time?.slice(0, 5) || null };
    this.amount =
      p.draft.occasionalAmountCents == null
        ? ''
        : (p.draft.occasionalAmountCents / 100).toFixed(2).replace('.', ',');
  }
  async interpret() {
    if (this.busy() || !this.message.trim()) return;
    this.setBusy(true);
    this.error.set('');
    try {
      const p = await this.api.request<Proposal>(
        `/groups/${this.club().id}/assistant/proposals`,
        'POST',
        {
          message: this.message.trim(),
          previousProposalId: this.proposal()?.id || null,
        },
      );
      if (this.destroyed) return;
      this.accept(p);
      this.reviewed.set(false);
      this.message = '';
      this.focus('assistant-details');
    } catch (e) {
      this.fail(e);
    } finally {
      if (!this.destroyed) this.setBusy(false);
    }
  }
  async review() {
    const p = this.proposal();
    if (this.busy() || !p || !this.draft) return;
    this.setBusy(true);
    this.error.set('');
    try {
      const d = this.draft;
      let cents: number | null = null;
      if (d.chargeOccasional) {
        if (!/^\d{1,7}([,.]\d{1,2})?$/.test(this.amount.trim()))
          throw new Error('Informe um valor avulso válido.');
        cents = Math.round(Number(this.amount.trim().replace(',', '.')) * 100);
      }
      const revised = await this.api.request<Proposal>(`/assistant/proposals/${p.id}`, 'PUT', {
        version: p.version,
        draft: {
          ...d,
          title: d.title?.trim() || null,
          location: d.location?.trim() || null,
          date: d.date || null,
          time: d.time || null,
          teamCount: d.teamCount == null ? null : Number(d.teamCount),
          teamSize: d.teamSize == null ? null : Number(d.teamSize),
          recurrenceEndsOn: d.recurring ? d.recurrenceEndsOn || null : null,
          occasionalAmountCents: cents,
        },
      });
      if (this.destroyed) return;
      this.accept(revised);
      this.reviewed.set(revised.ready);
      this.focus(revised.ready ? 'assistant-summary' : 'assistant-questions');
    } catch (e) {
      this.fail(e);
    } finally {
      if (!this.destroyed) this.setBusy(false);
    }
  }
  edit() {
    this.reviewed.set(false);
    this.error.set('');
    this.focus('assistant-details');
  }
  restart() {
    this.proposal.set(null);
    this.draft = null;
    this.reviewed.set(false);
    this.error.set('');
    this.message = '';
    this.focus('assistant-request');
  }
  async confirm() {
    const p = this.proposal();
    if (this.busy() || !p || !this.reviewed()) return;
    this.setBusy(true);
    this.error.set('');
    try {
      const detail = await this.api.request<Detail>(
        `/assistant/proposals/${p.id}/confirm`,
        'POST',
        { version: p.version },
      );
      if (!this.destroyed) {
        this.setBusy(false);
        this.created.emit(detail);
      }
    } catch (e) {
      this.fail(e);
    } finally {
      if (!this.destroyed) this.setBusy(false);
    }
  }
}

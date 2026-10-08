import { Component, OnChanges, OnDestroy, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { AttendanceReview } from './career-models';

@Component({
  selector: 'app-attendance-review',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './attendance-review.html',
  styleUrl: './attendance-review.css',
})
export class AttendanceReviewPage implements OnChanges, OnDestroy {
  gameId = input.required<string>();
  timeZone = input('America/Sao_Paulo');
  private api = inject(Api);
  private version = 0;
  review = signal<AttendanceReview | null>(null);
  answers = signal<Record<string, boolean | null>>({});
  loading = signal(true);
  busy = signal(false);
  confirming = signal(false);
  error = signal('');
  notice = signal('');
  happened = false;
  complete = computed(
    () =>
      !!this.review()?.players.length &&
      this.review()!.players.every(
        (p) => this.answers()[p.playerId] !== null && this.answers()[p.playerId] !== undefined,
      ),
  );
  present = computed(
    () => this.review()?.players.filter((p) => this.answers()[p.playerId] === true) || [],
  );
  ngOnChanges() {
    void this.load();
  }
  ngOnDestroy() {
    this.version++;
  }
  private accept(review: AttendanceReview) {
    this.review.set(review);
    this.answers.set(Object.fromEntries(review.players.map((p) => [p.playerId, p.present])));
    this.happened = false;
    this.confirming.set(false);
  }
  async load() {
    const id = this.gameId(),
      version = ++this.version;
    this.loading.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const review = await this.api.request<AttendanceReview>(`/games/${id}/attendance-review`);
      if (version === this.version) this.accept(review);
    } catch (e) {
      if (version === this.version) this.error.set((e as Error).message);
    } finally {
      if (version === this.version) this.loading.set(false);
    }
  }
  answer(playerId: string, value: boolean | null) {
    this.answers.update((a) => ({ ...a, [playerId]: value }));
    this.confirming.set(false);
  }
  markAll(present: boolean) {
    this.answers.set(Object.fromEntries(this.review()!.players.map((p) => [p.playerId, present])));
    this.confirming.set(false);
  }
  preview() {
    if (this.complete() && this.happened && !this.busy()) {
      this.confirming.set(true);
      setTimeout(() => document.getElementById('attendance-summary')?.focus());
    }
  }
  async save() {
    if (!this.confirming() || !this.happened || !this.complete() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const id = this.gameId(),
      version = this.version;
    try {
      const review = await this.api.request<AttendanceReview>(
        `/games/${id}/attendance-review`,
        'PUT',
        {
          version: this.review()!.version,
          happened: this.happened,
          players: this.review()!.players.map((p) => ({
            playerId: p.playerId,
            present: this.answers()[p.playerId],
          })),
        },
      );
      if (version === this.version) {
        this.accept(review);
        this.notice.set('Presenças conferidas. As conquistas da turma foram atualizadas.');
      }
    } catch (e) {
      if (version === this.version) this.error.set((e as Error).message);
    } finally {
      if (version === this.version) this.busy.set(false);
    }
  }
  date(value: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: this.timeZone(),
    }).format(new Date(value));
  }
}

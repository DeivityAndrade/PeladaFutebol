import { Component, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, ApiError } from './api';
import { Detail } from './models';
import { GroupPlayer, positionLabel } from './group-players';
interface DrawPlayer extends Omit<GroupPlayer, 'playerId'> {
  playerId: string;
  fixed: boolean;
  guestGoalkeeper: boolean;
}
interface DrawTeam {
  teamId: string;
  name: string;
  color: string;
  reservedPlaces: number;
  estimatedAverage: number;
  players: DrawPlayer[];
}
interface Preview {
  id: string;
  mode: string;
  expiresAt: string;
  unclassified: number;
  teams: DrawTeam[];
}
interface History {
  appliedAt: string;
  preview: Preview;
}
@Component({
  selector: 'app-team-draw',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  templateUrl: './team-draw.html',
  styleUrl: './team-draw.css',
})
export class TeamDraw {
  gameId = input.required<string>();
  clubId = input.required<string>();
  confirmed = input(0);
  applied = output<Detail>();
  cancelled = output<void>();
  classify = output<void>();
  processing = output<boolean>();
  private api = inject(Api);
  mode = 'BALANCED';
  preview = signal<Preview | null>(null);
  busy = signal(false);
  error = signal('');
  history = signal<History[]>([]);
  historyVisible = signal(false);
  historyLoaded = signal(false);
  positionLabel = positionLabel;
  private setBusy(value: boolean) {
    this.busy.set(value);
    this.processing.emit(value);
  }
  async generate() {
    if (this.busy()) return;
    this.setBusy(true);
    this.error.set('');
    this.preview.set(null);
    try {
      this.preview.set(
        await this.api.request<Preview>(`/games/${this.gameId()}/teams/draw/preview`, 'POST', {
          mode: this.mode,
        }),
      );
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.setBusy(false);
    }
  }
  async apply() {
    const preview = this.preview();
    if (!preview || this.busy()) return;
    this.setBusy(true);
    this.error.set('');
    try {
      this.applied.emit(
        await this.api.request<Detail>(
          `/games/${this.gameId()}/teams/draw/${preview.id}/apply`,
          'POST',
        ),
      );
    } catch (e) {
      this.error.set(
        e instanceof ApiError && (e.status === 409 || e.status === 404)
          ? 'A prévia mudou ou expirou. Gere uma nova combinação antes de aplicar.'
          : (e as Error).message,
      );
      if (e instanceof ApiError && [409, 404].includes(e.status)) this.preview.set(null);
    } finally {
      this.setBusy(false);
    }
  }
  async toggleHistory() {
    this.historyVisible.update((v) => !v);
    if (!this.historyVisible() || this.historyLoaded()) return;
    this.setBusy(true);
    this.error.set('');
    try {
      this.history.set(
        await this.api.request<History[]>(`/games/${this.gameId()}/teams/draw/history`),
      );
      this.historyLoaded.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.setBusy(false);
    }
  }
  formatDate(value: string) {
    return new Date(value).toLocaleString('pt-BR');
  }
}

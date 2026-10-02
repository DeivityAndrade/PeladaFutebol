import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { Api, ApiError } from './api';
import { Icon } from './icon';

interface AdminSummary {
  generatedAt: string;
  timeZone: string;
  totalAccounts: number;
  totalGroups: number;
  newLast7Days: number;
  newThisMonth: number;
  undatedAccounts: number;
  visits: {
    enabled: boolean;
    startedAt: string | null;
    total: number;
    today: number;
    last7Days: number;
    thisMonth: number;
  };
  months: { period: string; registrations: number; visits: number }[];
}

@Component({
  selector: 'app-admin-page',
  standalone: true,
  imports: [Icon],
  templateUrl: './admin-page.html',
  styleUrl: './admin-page.css',
})
export class AdminPage implements OnInit, OnDestroy {
  private api = inject(Api);
  private disposed = false;
  data = signal<AdminSummary | null>(null);
  loading = signal(true);
  error = signal('');
  denied = signal(false);
  range = signal(6);
  metric = signal<'registrations' | 'visits'>('registrations');
  months = computed(() => this.data()?.months.slice(-this.range()) || []);
  max = computed(() => Math.max(1, ...this.months().map((month) => month[this.metric()])));
  periodCount = computed(() => this.months().reduce((sum, month) => sum + month[this.metric()], 0));

  dateLabel(iso: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      dateStyle: 'short',
      timeZone: 'America/Sao_Paulo',
    }).format(new Date(iso));
  }

  ngOnInit() {
    void this.load();
  }
  ngOnDestroy() {
    this.disposed = true;
  }

  async load() {
    if (this.loading() && this.data()) return;
    this.loading.set(true);
    this.error.set('');
    this.denied.set(false);
    try {
      const data = await this.api.request<AdminSummary>('/admin/summary');
      if (!this.disposed) this.data.set(data);
    } catch (error) {
      if (this.disposed) return;
      this.data.set(null);
      this.denied.set(error instanceof ApiError && [401, 403].includes(error.status));
      this.error.set(
        error instanceof Error ? error.message : 'Não foi possível carregar os cadastros.',
      );
    } finally {
      if (!this.disposed) this.loading.set(false);
    }
  }

  number(value: number) {
    return new Intl.NumberFormat('pt-BR').format(value);
  }
  monthLabel(period: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(period + '-01T12:00:00Z'));
  }
  updatedAt() {
    const data = this.data();
    return data
      ? new Intl.DateTimeFormat('pt-BR', {
          dateStyle: 'short',
          timeStyle: 'short',
          timeZone: data.timeZone,
        }).format(new Date(data.generatedAt))
      : '';
  }
}

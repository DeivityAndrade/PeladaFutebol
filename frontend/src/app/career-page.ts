import {
  Component,
  OnInit,
  OnChanges,
  OnDestroy,
  inject,
  input,
  signal,
  computed,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Icon } from './icon';
import { Figurinha } from './figurinha';
import { CareerData, CareerGroup, FigurinhaData, achievementNames } from './career-models';
import { User } from './models';

@Component({
  selector: 'app-career-page',
  standalone: true,
  imports: [FormsModule, Icon, Figurinha],
  templateUrl: './career-page.html',
  styleUrl: './career-page.css',
})
export class CareerPage implements OnInit, OnChanges, OnDestroy {
  clubId = input('');
  heading = input('Minha carreira');
  account = input<User | null>(null);
  private api = inject(Api);
  data = signal<CareerData | null>(null);
  directory = signal<CareerGroup[]>([]);
  loading = signal(true);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  selected = '';
  title = '';
  frame = '';
  badges: string[] = [];
  shared = false;
  names = achievementNames;
  private requestVersion = 0;
  preview = computed<FigurinhaData | null>(() => {
    const card = this.data()?.card;
    const user = this.account();
    return card ? (user ? { ...card, name: user.name, photoUrl: user.photoUrl } : card) : null;
  });
  next = computed(() => this.data()?.achievements.find((a) => !a.awardedAt));
  unseen = computed(() => this.data()?.achievements.filter((a) => a.unseen) || []);
  ngOnChanges() {
    if (this.clubId()) {
      this.selected = this.clubId();
      void this.load();
    }
  }
  async ngOnInit() {
    if (this.clubId()) return;
    const version = ++this.requestVersion;
    try {
      const groups = await this.api.request<CareerGroup[]>('/career/groups');
      if (version !== this.requestVersion) return;
      this.directory.set(groups);
      this.selected = groups[0]?.clubId || '';
      if (this.selected) await this.load();
      else this.loading.set(false);
    } catch (e) {
      if (version === this.requestVersion) {
        this.error.set((e as Error).message);
        this.loading.set(false);
      }
    }
  }
  ngOnDestroy() {
    this.requestVersion++;
  }
  async load() {
    if (!this.selected) {
      await this.ngOnInit();
      return;
    }
    const id = this.selected,
      version = ++this.requestVersion;
    this.loading.set(true);
    this.error.set('');
    this.notice.set('');
    this.data.set(null);
    try {
      const data = await this.api.request<CareerData>(`/groups/${id}/career/me`);
      if (version !== this.requestVersion) return;
      this.accept(data);
    } catch (e) {
      if (version === this.requestVersion) this.error.set((e as Error).message);
    } finally {
      if (version === this.requestVersion) this.loading.set(false);
    }
  }
  private accept(data: CareerData) {
    this.data.set(data);
    this.title = data.card.title || '';
    this.frame = data.card.frame || '';
    this.badges = [...data.card.badges];
    this.shared = data.card.shared;
  }
  async configure(active: boolean) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.request(`/groups/${this.selected}/career/program`, 'PUT', { active });
      this.accept(await this.api.request<CareerData>(`/groups/${this.selected}/career/me`));
      this.notice.set(
        active
          ? 'Conquistas ativadas. As próximas peladas já podem fazer parte da sua história.'
          : 'Conquistas pausadas. A coleção de todos foi preservada.',
      );
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
  unlocked(code: string) {
    return !!this.data()?.achievements.find((a) => a.code === code)?.awardedAt;
  }
  toggleBadge(code: string, checked: boolean) {
    this.badges = checked ? [...this.badges, code] : this.badges.filter((b) => b !== code);
  }
  viewCollection() {
    const collection = document.getElementById('career-collection');
    collection?.focus();
    collection?.scrollIntoView({ block: 'start' });
  }
  async save() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const card = await this.api.request<FigurinhaData>(
        `/groups/${this.selected}/career/card`,
        'PUT',
        {
          title: this.title || null,
          frame: this.frame || null,
          badges: this.badges,
          shared: this.shared,
        },
      );
      this.data.update((d) => (d ? { ...d, card } : d));
      this.notice.set('Figurinha salva.');
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
  async dismiss() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.request(`/groups/${this.selected}/career/seen`, 'POST');
      this.data.update((d) =>
        d
          ? {
              ...d,
              correctionUnseen: false,
              achievements: d.achievements.map((a) => ({ ...a, unseen: false })),
            }
          : d,
      );
      requestAnimationFrame(() =>
        document.getElementById('career-collection')?.focus({ preventScroll: true }),
      );
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
  date(value: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: this.data()?.timeZone || 'America/Sao_Paulo',
    }).format(new Date(value));
  }
}

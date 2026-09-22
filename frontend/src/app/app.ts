import {
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  signal,
  computed,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, ApiError } from './api';
import { Club, Detail, Game, Player, Team, User } from './models';
import { Icon } from './icon';
import { Pitch } from './pitch';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, Icon, Pitch],
  templateUrl: './app.html',
})
export class App implements OnInit, OnDestroy {
  private api = inject(Api);
  user = signal<User | null>(null);
  clubs = signal<Club[]>([]);
  games = signal<Game[]>([]);
  detail = signal<Detail | null>(null);
  club = signal<Club | null>(null);
  page = signal('demo');
  loading = signal(true);
  busy = signal(false);
  error = signal('');
  toast = signal('');
  modal = signal('');
  authMode = 'login';
  teamId = signal('');
  tab = signal('lineup');
  sidebar = signal(false);
  form: Record<string, any> = {};
  private timer?: ReturnType<typeof setInterval>;
  private noticeTimer?: ReturnType<typeof setTimeout>;
  private routeVersion = 0;
  team = computed(
    () => this.detail()?.teams.find((t) => t.id === this.teamId()) || this.detail()?.teams[0],
  );
  demo = computed(() => this.page() === 'demo');
  owner = computed(
    () => !!this.user() && this.detail()?.club.ownerId === this.user()!.id && !this.demo(),
  );
  editable = computed(() => !!this.detail()?.game.editable && !this.demo());
  captain = computed(() => this.editable() && this.team()?.captainId === this.user()?.id);
  roster = computed(
    () => this.detail()?.attendees.filter((p) => p.teamId === this.team()?.id) || [],
  );
  benchCount = computed(() => this.roster().filter((p) => p.slot === null).length);
  available = computed(
    () => this.detail()?.attendees.filter((p) => p.status === 'CONFIRMED' && !p.teamId) || [],
  );
  confirmed = computed(
    () => this.detail()?.attendees.filter((p) => p.status === 'CONFIRMED') || [],
  );
  waiting = computed(() => this.detail()?.attendees.filter((p) => p.status === 'WAITING') || []);
  mine = computed(() => this.detail()?.attendees.find((p) => p.id === this.user()?.id));
  capacity = computed(
    () => (this.detail()?.game.teamCount || 0) * (this.detail()?.game.teamSize || 0),
  );

  async ngOnInit() {
    try {
      this.user.set(await this.api.request<User>('/auth/me'));
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) this.showError(e);
    }
    await this.route();
    this.timer = setInterval(() => {
      if (
        !document.hidden &&
        !this.busy() &&
        !this.loading() &&
        !this.modal() &&
        this.page() === 'game'
      )
        void this.refresh();
    }, 15000);
  }
  ngOnDestroy() {
    clearInterval(this.timer);
    clearTimeout(this.noticeTimer);
  }
  @HostListener('window:hashchange') async route() {
    const version = ++this.routeVersion;
    const parts = location.hash.slice(1).split('/');
    const page = parts[0] || 'demo';
    this.page.set(page);
    this.sidebar.set(false);
    this.loading.set(true);
    this.error.set('');
    try {
      if (page === 'demo') {
        const d = await this.api.request<Detail>('/demo');
        if (version === this.routeVersion) this.detail.set(d);
      } else if (!this.user()) {
        this.openAuth();
      } else if (page === 'groups') {
        this.clubs.set(await this.api.request<Club[]>('/groups'));
      } else if (page === 'group') {
        const clubs = await this.api.request<Club[]>('/groups');
        this.clubs.set(clubs);
        this.club.set(clubs.find((c) => c.id === parts[1]) || null);
        this.games.set(await this.api.request<Game[]>('/groups/' + parts[1] + '/games'));
      } else if (page === 'game') {
        const d = await this.api.request<Detail>('/games/' + parts[1]);
        if (version === this.routeVersion) this.detail.set(d);
      } else if (page === 'invite') {
        this.form = { invite: parts[1] };
        this.modal.set('join');
      } else {
        this.navigate('demo');
      }
    } catch (e) {
      this.showError(e);
    } finally {
      if (version === this.routeVersion) this.loading.set(false);
    }
  }
  navigate(path: string) {
    if (location.hash === '#' + path) void this.route();
    else location.hash = path;
  }
  skipToMain(event: Event) {
    event.preventDefault();
    document.getElementById('main')?.focus();
  }
  async refresh() {
    const id = this.detail()?.game.id;
    if (!id || this.demo()) return;
    const version = this.routeVersion;
    try {
      const d = await this.api.request<Detail>('/games/' + id);
      if (version === this.routeVersion) this.detail.set(d);
    } catch (e) {
      this.showError(e);
    }
  }
  async action(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await work();
    } catch (e) {
      this.showError(e);
      if (e instanceof ApiError && e.status === 409) await this.refresh();
    } finally {
      this.busy.set(false);
    }
  }
  showError(e: unknown) {
    this.error.set(e instanceof Error ? e.message : 'Não foi possível concluir. Tente novamente.');
  }
  notify(message: string) {
    this.toast.set(message);
    clearTimeout(this.noticeTimer);
    this.noticeTimer = setTimeout(() => this.toast.set(''), 4500);
  }
  openAuth(mode = 'login') {
    this.authMode = mode;
    this.form = {};
    this.modal.set('auth');
  }
  open(name: string) {
    this.error.set('');
    this.form = {};
    if (name === 'game') this.form = { title: 'Pelada da semana', teamCount: 2, teamSize: 7 };
    if (name === 'team' && this.team())
      this.form = { ...this.team(), captainId: this.team()!.captainId || '' };
    this.modal.set(name);
  }
  closeModal() {
    if (!this.busy()) this.modal.set('');
  }
  @HostListener('document:keydown.escape') escape() {
    this.closeModal();
    this.sidebar.set(false);
  }
  @HostListener('document:keydown.tab', ['$event']) trapFocus(rawEvent: Event) {
    const event = rawEvent as KeyboardEvent;
    if (!this.modal()) return;
    const elements = Array.from(
      document.querySelectorAll<HTMLElement>(
        '.modal button:not(:disabled),.modal input,.modal select,.modal textarea,.modal a[href]',
      ),
    );
    const first = elements[0],
      last = elements.at(-1);
    if (!first) return;
    if (!document.querySelector('.modal')?.contains(document.activeElement)) {
      event.preventDefault();
      first.focus();
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
  submitAuth() {
    void this.action(async () => {
      if (this.authMode === 'register') await this.api.request('/auth/register', 'POST', this.form);
      this.user.set(
        await this.api.request<User>('/auth/login', 'POST', {
          email: this.form['email'],
          password: this.form['password'],
        }),
      );
      this.modal.set('');
      this.notify('Bom jogo! Você entrou na sua conta.');
      if (this.page() === 'demo') this.navigate('groups');
      else await this.route();
    });
  }
  logout() {
    void this.action(async () => {
      await this.api.request('/auth/logout', 'POST');
      this.user.set(null);
      this.detail.set(null);
      this.navigate('demo');
    });
  }
  createClub() {
    void this.action(async () => {
      const c = await this.api.request<Club>('/groups', 'POST', {
        name: this.form['name'],
        description: this.form['description'] || '',
      });
      this.modal.set('');
      this.navigate('group/' + c.id);
      this.notify('Grupo criado. Convide a galera!');
    });
  }
  join() {
    void this.action(async () => {
      const raw = (this.form['invite'] || '').trim();
      const code = raw.split('/').at(-1);
      const c = await this.api.request<Club>(
        '/invites/' + encodeURIComponent(code) + '/join',
        'POST',
      );
      this.modal.set('');
      this.navigate('group/' + c.id);
      this.notify('Você entrou no grupo.');
    });
  }
  createGame() {
    void this.action(async () => {
      const d = await this.api.request<Detail>('/groups/' + this.club()!.id + '/games', 'POST', {
        ...this.form,
        startsAt: new Date(this.form['startsAt']).toISOString(),
      });
      this.detail.set(d);
      this.modal.set('');
      this.navigate('game/' + d.game.id);
      this.notify('Pelada marcada!');
    });
  }
  configureTeam() {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>(this.teamPath(), 'PUT', {
          name: this.form['name'],
          color: this.form['color'],
          captainId: this.form['captainId'] || null,
        }),
      );
      this.modal.set('');
      this.notify('Time atualizado.');
    });
  }
  teamPath() {
    return '/games/' + this.detail()!.game.id + '/teams/' + this.team()!.id;
  }
  attend() {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>('/games/' + this.detail()!.game.id + '/attendance', 'POST'),
      );
      this.notify(
        this.mine()?.status === 'WAITING'
          ? 'Você entrou na lista de espera.'
          : 'Presença confirmada. Bora jogar!',
      );
    });
  }
  leave() {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>(
          '/games/' + this.detail()!.game.id + '/attendance',
          'DELETE',
        ),
      );
      this.modal.set('');
      this.notify('Sua participação foi cancelada.');
    });
  }
  cancel() {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>('/games/' + this.detail()!.game.id + '/cancel', 'POST'),
      );
      this.modal.set('');
      this.notify('Pelada cancelada.');
    });
  }
  pick(player: Player) {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>(this.teamPath() + '/players', 'POST', {
          playerId: player.id,
        }),
      );
      this.notify(player.name.split(' ')[0] + ' entrou no time.');
    });
  }
  release(player: Player) {
    void this.action(async () => {
      this.detail.set(
        await this.api.request<Detail>(this.teamPath() + '/players/' + player.id, 'DELETE'),
      );
      this.notify('Jogador disponível para outros times.');
    });
  }
  saveLineup(body: unknown) {
    void this.action(async () => {
      this.detail.set(await this.api.request<Detail>(this.teamPath() + '/lineup', 'PUT', body));
      this.notify('Escalação salva.');
    });
  }
  async share(club: Club | null) {
    if (!club?.invite) {
      this.notify('Crie seu grupo para convidar a galera.');
      return;
    }
    const link = location.origin + location.pathname + '#invite/' + club.invite;
    try {
      await navigator.clipboard.writeText(link);
      this.notify('Convite copiado. É só compartilhar com a galera!');
    } catch {
      this.form = { link };
      this.modal.set('share');
    }
  }
  initials(name: string) {
    return name
      .split(' ')
      .slice(0, 2)
      .map((v) => v[0])
      .join('');
  }
  captainName(team: Team) {
    return this.detail()?.attendees.find((p) => p.id === team.captainId)?.name || 'A definir';
  }
  teamName(id: string | null) {
    return this.detail()?.teams.find((t) => t.id === id)?.name || 'Sem time';
  }
  teamColor(id: string | null) {
    return this.detail()?.teams.find((t) => t.id === id)?.color || '#e8ebe3';
  }
  date(iso: string, format: 'full' | 'day' | 'month' | 'hour' = 'full') {
    const opts: Intl.DateTimeFormatOptions =
      format === 'full'
        ? { weekday: 'long', day: 'numeric', month: 'long' }
        : format === 'day'
          ? { day: '2-digit' }
          : format === 'month'
            ? { month: 'short' }
            : { hour: '2-digit', minute: '2-digit' };
    return new Intl.DateTimeFormat('pt-BR', { ...opts, timeZone: 'America/Sao_Paulo' }).format(
      new Date(iso),
    );
  }
  eligibleCaptains() {
    return this.confirmed().filter((p) => !p.teamId || p.teamId === this.team()?.id);
  }
}

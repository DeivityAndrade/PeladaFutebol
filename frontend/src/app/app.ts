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
import {
  Barbecue,
  Club,
  Detail,
  FinanceCharge,
  FinanceMember,
  FinanceSummary,
  Game,
  Player,
  PlayerProfile,
  SocialSchedule,
  Team,
  User,
} from './models';
import { Icon } from './icon';
import { Brand } from './brand';
import { Pitch } from './pitch';
import { SocialPage } from './social-page';

function currentBillingPeriod() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date());
  return `${parts.find((part) => part.type === 'year')?.value}-${parts.find((part) => part.type === 'month')?.value}`;
}

function localDateTimeInZone(value: string, timeZone: string) {
  const [date, time] = value.split('T');
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const target = Date.UTC(year, month - 1, day, hour, minute);
  let candidate = target;
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  for (let attempt = 0; attempt < 4; attempt++) {
    const parts = formatter.formatToParts(new Date(candidate));
    const part = (name: string) => Number(parts.find((item) => item.type === name)?.value);
    const actual = Date.UTC(
      part('year'),
      part('month') - 1,
      part('day'),
      part('hour'),
      part('minute'),
      part('second'),
    );
    candidate += target - actual;
  }
  const check = formatter.formatToParts(new Date(candidate));
  const rendered = `${check.find((item) => item.type === 'year')?.value}-${check.find((item) => item.type === 'month')?.value}-${check.find((item) => item.type === 'day')?.value}T${check.find((item) => item.type === 'hour')?.value}:${check.find((item) => item.type === 'minute')?.value}`;
  if (rendered !== value)
    throw new Error('Esse horário não existe no fuso do grupo. Escolha outro.');
  return new Date(candidate).toISOString();
}

function dateTimeForZone(iso: string, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(iso));
  const part = (name: string) => parts.find((item) => item.type === name)?.value || '';
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, Icon, Pitch, SocialPage, Brand],
  templateUrl: './app.html',
})
export class App implements OnInit, OnDestroy {
  private api = inject(Api);
  user = signal<User | null>(null);
  clubs = signal<Club[]>([]);
  games = signal<Game[]>([]);
  friendlies = signal<SocialSchedule[]>([]);
  detail = signal<Detail | null>(null);
  club = signal<Club | null>(null);
  barbecues = signal<Barbecue[]>([]);
  financeData = signal<FinanceSummary | null>(null);
  financePeriod = signal(currentBillingPeriod());
  financePlayerFilter = signal('');
  financeGameFilter = signal('');
  financeLoading = signal(false);
  barbecueInvite = signal<Barbecue | null>(null);
  page = signal('demo');
  resetToken = signal('');
  authNotice = signal('');
  loading = signal(true);
  busy = signal(false);
  error = signal('');
  toast = signal('');
  modal = signal('');
  authMode = 'login';
  teamId = signal('');
  tab = signal('lineup');
  groupTab = signal('games');
  gameListTab = signal<'upcoming' | 'finished' | 'cancelled'>('upcoming');
  sidebar = signal(false);
  theme = signal<'light' | 'dark'>(
    document.documentElement.dataset['theme'] === 'dark' ? 'dark' : 'light',
  );
  demoFinished = signal(false);
  now = signal(Date.now());
  profile = signal<PlayerProfile | null>(null);
  private serverOffset = 0;
  private secondTimer?: ReturnType<typeof setInterval>;
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
  groupOwner = computed(() => !!this.user() && this.club()?.ownerId === this.user()!.id);
  socialAccess = computed(
    () => !!this.user() && this.clubs().some((club) => club.ownerId === this.user()!.id),
  );
  barbecueSeriesConfigured = computed(() => this.barbecues().some((event) => event.recurring));
  editable = computed(() => !!this.detail()?.game.editable && !this.demo());
  teamEditable = computed(() => !!this.detail()?.game.teamEditable && !this.demo());
  captain = computed(() => this.teamEditable() && this.team()?.captainId === this.user()?.id);
  roster = computed(
    () => this.detail()?.attendees.filter((p) => p.teamId === this.team()?.id) || [],
  );
  starters = computed(() =>
    this.roster()
      .filter((p) => p.slot !== null)
      .sort((a, b) => a.slot! - b.slot!),
  );
  reserves = computed(() => this.roster().filter((p) => p.slot === null));
  rosterDisplay = computed(() => [...this.starters(), ...this.reserves()]);
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
  squadNumber(player: Player) {
    return this.roster().findIndex((member) => member.id === player.id) + 1;
  }
  myNumber = computed(() => {
    const me = this.mine();
    if (!me?.teamId) return 0;
    const squad = this.detail()!.attendees.filter((p) => p.teamId === me.teamId);
    return squad.findIndex((p) => p.id === me.id) + 1;
  });
  waitingPosition = computed(() => this.waiting().findIndex((p) => p.id === this.user()?.id) + 1);
  spotsLeft = computed(() => Math.max(0, this.capacity() - (this.detail()?.game.confirmed || 0)));
  spotsDash = computed(() => {
    const circumference = 2 * Math.PI * 26;
    const ratio = this.capacity()
      ? Math.min(1, (this.detail()?.game.confirmed || 0) / this.capacity())
      : 0;
    return `${(circumference * ratio).toFixed(1)} ${circumference.toFixed(1)}`;
  });
  live = computed(() => this.detail()?.game.matchStatus === 'LIVE');
  finished = computed(() => this.detail()?.game.matchStatus === 'FINISHED');
  canStart = computed(() => {
    const d = this.detail();
    return (
      !!d &&
      !this.demo() &&
      d.game.matchStatus === 'READY' &&
      d.attendees.filter((p) => p.status === 'CONFIRMED').length > 0 &&
      d.attendees.every((p) => p.status !== 'CONFIRMED' || !!p.teamId) &&
      d.teams.every((t) => d.attendees.some((p) => p.teamId === t.id && p.status === 'CONFIRMED'))
    );
  });
  canScore = computed(
    () => !this.demo() && !!this.mine() && this.mine()?.status === 'CONFIRMED' && this.live(),
  );
  canChangeGoals = computed(
    () => this.canScore() || (this.canCorrect() && !!this.detail()?.game.correctionOpen),
  );
  canFinish = this.canScore;
  canCorrect = computed(() => this.owner() && this.finished());
  ratingOpen = computed(
    () =>
      this.finished() &&
      !this.demo() &&
      !!this.detail()?.ratingsVisibleAt &&
      this.now() + this.serverOffset < Date.parse(this.detail()!.ratingsVisibleAt!),
  );
  elapsedSeconds = computed(() => {
    const game = this.detail()?.game;
    if (!game?.matchStartedAt) return 0;
    if (game.matchDurationSeconds !== null) return game.matchDurationSeconds;
    return Math.max(
      0,
      Math.floor((this.now() + this.serverOffset - Date.parse(game.matchStartedAt)) / 1000),
    );
  });
  upcomingGames = computed(() =>
    this.games()
      .filter((game) => !this.isCancelledGame(game) && !this.isArchivedGame(game))
      .sort((a, b) => {
        const liveFirst = Number(b.matchStatus === 'LIVE') - Number(a.matchStatus === 'LIVE');
        return liveFirst || Date.parse(a.startsAt) - Date.parse(b.startsAt);
      }),
  );
  finishedGames = computed(() =>
    this.games()
      .filter((game) => this.isArchivedGame(game))
      .sort((a, b) => Date.parse(b.startsAt) - Date.parse(a.startsAt)),
  );
  cancelledGames = computed(() =>
    this.games()
      .filter((game) => this.isCancelledGame(game))
      .sort((a, b) => Date.parse(b.startsAt) - Date.parse(a.startsAt)),
  );
  visibleGames = computed(() => {
    switch (this.gameListTab()) {
      case 'finished':
        return this.finishedGames();
      case 'cancelled':
        return this.cancelledGames();
      default:
        return this.upcomingGames();
    }
  });
  pastWithoutResult = computed(() => {
    const game = this.detail()?.game;
    return !!game && this.isPastWithoutResult(game);
  });
  matchWasCancelled = computed(() => {
    const game = this.detail()?.game;
    return !!game && this.isCancelledGame(game);
  });
  matchSummaryUnavailable = computed(() => this.pastWithoutResult() || this.matchWasCancelled());
  matchTabVisible = computed(
    () => !!this.detail()?.game.liveEnabled || this.matchSummaryUnavailable(),
  );

  async ngOnInit() {
    try {
      this.user.set(await this.api.request<User>('/auth/me'));
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) this.showError(e);
    }
    if (this.user() && !location.hash) history.replaceState(null, '', '#groups');
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
    this.secondTimer = setInterval(() => this.now.set(Date.now()), 1000);
  }
  ngOnDestroy() {
    clearInterval(this.timer);
    clearInterval(this.secondTimer);
    clearTimeout(this.noticeTimer);
  }
  @HostListener('window:hashchange') async route() {
    const version = ++this.routeVersion;
    const parts = location.hash.slice(1).split('/');
    const page = parts[0] || 'demo';
    this.page.set(page);
    this.modal.set('');
    this.sidebar.set(false);
    this.loading.set(true);
    this.error.set('');
    this.authNotice.set('');
    if (page !== 'reset-password') this.resetToken.set('');
    if (page !== 'barbecue-invite') this.barbecueInvite.set(null);
    try {
      if (page === 'demo') {
        const d = await this.api.request<Detail>(this.demoFinished() ? '/demo/finished' : '/demo');
        if (version === this.routeVersion) this.setDetail(d);
      } else if (page === 'reset-password') {
        this.resetToken.set(parts[1] || '');
      } else if (!this.user()) {
        this.openAuth();
      } else if (page === 'groups') {
        this.clubs.set(await this.api.request<Club[]>('/groups'));
      } else if (page === 'social') {
        this.clubs.set(await this.api.request<Club[]>('/groups'));
        if (!this.clubs().some((club) => club.ownerId === this.user()?.id)) {
          this.navigate('groups');
          return;
        }
      } else if (page === 'group') {
        const clubs = await this.api.request<Club[]>('/groups');
        this.clubs.set(clubs);
        this.club.set(clubs.find((c) => c.id === parts[1]) || null);
        const games = await this.api.request<Game[]>('/groups/' + parts[1] + '/games');
        this.serverOffset = games.length ? Date.parse(games[0].serverNow) - Date.now() : 0;
        this.now.set(Date.now());
        this.games.set(games);
        this.friendlies.set(
          await this.api.request<SocialSchedule[]>('/groups/' + parts[1] + '/friendlies'),
        );
        this.barbecues.set(
          await this.api.request<Barbecue[]>('/groups/' + parts[1] + '/barbecues'),
        );
        this.financeData.set(null);
        this.financePlayerFilter.set('');
        this.financeGameFilter.set('');
        if (this.groupTab() === 'finance') await this.loadFinance();
      } else if (page === 'barbecue-invite') {
        this.barbecueInvite.set(
          await this.api.request<Barbecue>('/barbecue-invites/' + encodeURIComponent(parts[1])),
        );
      } else if (page === 'game') {
        const d = await this.api.request<Detail>('/games/' + parts[1]);
        if (version === this.routeVersion) {
          this.setDetail(d);
          this.tab.set(this.shouldOpenMatchSummary(d.game) ? 'match' : 'lineup');
        }
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
  toggleTheme() {
    const next = this.theme() === 'dark' ? 'light' : 'dark';
    this.theme.set(next);
    document.documentElement.dataset['theme'] = next;
    document
      .querySelector<HTMLMetaElement>('meta[name="theme-color"]')
      ?.setAttribute('content', next === 'dark' ? '#0b1510' : '#10241a');
    try {
      localStorage.setItem('pelada.theme', next);
    } catch {
      // The preference still applies to the current page when storage is unavailable.
    }
  }
  openSidebar() {
    this.sidebar.set(true);
    requestAnimationFrame(() => document.querySelector<HTMLElement>('.sidebar nav a')?.focus());
  }
  closeSidebar(restoreFocus = false) {
    const wasOpen = this.sidebar();
    this.sidebar.set(false);
    if (wasOpen && restoreFocus)
      requestAnimationFrame(() => document.querySelector<HTMLElement>('.mobile-menu')?.focus());
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
      if (version === this.routeVersion) this.setDetail(d);
    } catch (e) {
      this.showError(e);
    }
  }
  setDetail(detail: Detail | null) {
    if (detail) this.serverOffset = Date.parse(detail.game.serverNow) - Date.now();
    this.detail.set(detail);
  }
  private isCancelledGame(game: Game) {
    return game.cancelled || game.matchStatus === 'CANCELLED';
  }
  private hasGameTimePassed(game: Game) {
    const startsAt = Date.parse(game.startsAt);
    return Number.isFinite(startsAt) && startsAt <= this.now() + this.serverOffset;
  }
  private isArchivedGame(game: Game) {
    if (this.isCancelledGame(game)) return false;
    if (game.matchStatus === 'FINISHED') return true;
    return game.matchStatus !== 'LIVE' && this.hasGameTimePassed(game);
  }
  private isPastWithoutResult(game: Game) {
    return (
      !this.isCancelledGame(game) &&
      game.matchStatus !== 'FINISHED' &&
      game.matchStatus !== 'LIVE' &&
      this.hasGameTimePassed(game)
    );
  }
  private shouldOpenMatchSummary(game: Game) {
    return (
      game.matchStatus === 'FINISHED' ||
      this.isPastWithoutResult(game) ||
      this.isCancelledGame(game)
    );
  }
  gameStatusLabel(game: Game) {
    if (this.isCancelledGame(game)) return 'Cancelada';
    if (game.matchStatus === 'LIVE') return 'Ao vivo';
    if (game.matchStatus === 'FINISHED') return 'Finalizada';
    if (this.isPastWithoutResult(game)) return 'Resultado não registrado';
    return game.editable ? 'Inscrições abertas' : 'Aguardando início';
  }
  gameCardSummary(game: Game) {
    if (this.isCancelledGame(game)) return 'Evento cancelado';
    if (game.matchStatus === 'FINISHED') return 'Ver placar e súmula';
    if (this.isPastWithoutResult(game)) return 'Sem resultado';
    if (game.matchStatus === 'LIVE') return 'Acompanhar ao vivo';
    return `${game.confirmed}/${game.teamCount * game.teamSize} confirmados`;
  }
  gameListEmptyTitle() {
    if (this.gameListTab() === 'finished') return 'Nenhuma partida finalizada.';
    if (this.gameListTab() === 'cancelled') return 'Nenhuma pelada cancelada.';
    if (this.games().length) return 'Nenhuma pelada próxima.';
    return 'A quadra está esperando.';
  }
  gameListEmptyMessage() {
    if (this.gameListTab() === 'finished') {
      return 'Quando uma pelada passar ou for encerrada, ela aparecerá aqui com o resultado disponível.';
    }
    if (this.gameListTab() === 'cancelled') {
      return 'As peladas canceladas do grupo aparecerão nesta lista.';
    }
    if (this.games().length) {
      return 'As partidas passadas e canceladas estão nas abas ao lado.';
    }
    return this.groupOwner()
      ? 'Marque a primeira pelada do grupo.'
      : 'O organizador ainda não marcou uma pelada.';
  }
  moveGameListTab(event: KeyboardEvent, current: 'upcoming' | 'finished' | 'cancelled') {
    const tabs = ['upcoming', 'finished', 'cancelled'] as const;
    let nextIndex = tabs.indexOf(current);
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextIndex = (nextIndex + 1) % 3;
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextIndex = (nextIndex + 2) % 3;
    else if (event.key === 'Home') nextIndex = 0;
    else if (event.key === 'End') nextIndex = 2;
    else return;

    event.preventDefault();
    const next = tabs[nextIndex];
    this.gameListTab.set(next);
    requestAnimationFrame(() => document.getElementById(`game-filter-${next}`)?.focus());
  }
  showDemo(finished: boolean) {
    this.demoFinished.set(finished);
    this.tab.set(finished ? 'match' : 'lineup');
    void this.route();
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
    this.authNotice.set('');
    this.modal.set('auth');
  }
  forgotPassword() {
    this.authMode = 'forgot';
    this.authNotice.set('');
    this.error.set('');
  }
  open(name: string) {
    this.error.set('');
    this.form = {};
    if (name === 'game')
      this.form = {
        title: 'Pelada da semana',
        teamCount: 2,
        teamSize: 7,
        recurring: false,
        recurrenceEndsOn: '',
        chargeOccasional: !!this.club()?.occasionalAmountCents,
        occasionalAmount: this.toAmountInput(this.club()?.occasionalAmountCents ?? null),
      };
    if (name === 'club')
      this.form = {
        name: '',
        description: '',
        barbecueFrequency: 'NONE',
        monthlyAmount: '',
        billingDueDay: 10,
        occasionalAmount: '',
        pixInstructions: '',
        timeZone: this.browserGroupTimeZone(),
      };
    if (name === 'cancel') this.form = { scope: 'ONE' };
    if (name === 'edit-game' && this.detail())
      this.form = {
        title: this.detail()!.game.title,
        location: this.detail()!.game.location,
        startsAt: this.localDateTime(this.detail()!.game.startsAt, this.detail()!.club.timeZone),
        scope: 'ONE',
      };
    if (name === 'finance-settings') {
      const settings = this.financeData()?.settings || this.club();
      this.form = {
        monthlyAmount: this.toAmountInput(settings?.monthlyAmountCents ?? null),
        billingDueDay: settings?.billingDueDay ?? 10,
        occasionalAmount: this.toAmountInput(settings?.occasionalAmountCents ?? null),
        pixInstructions: settings?.pixInstructions ?? '',
      };
    }
    if (name === 'barbecue-series' || name === 'barbecue')
      this.form = { startsAt: '', location: '' };
    if (name === 'team' && this.team())
      this.form = { ...this.team(), captainId: this.team()!.captainId || '' };
    if (name === 'goal')
      this.form = {
        teamId: this.detail()?.teams[0]?.id || '',
        scorerId: '',
        ownGoal: false,
        minute: Math.floor(this.elapsedSeconds() / 60),
      };
    if (name === 'duration') this.form = { seconds: this.elapsedSeconds() };
    this.modal.set(name);
  }
  closeModal() {
    if (this.busy()) return;
    const wasAuth = this.modal() === 'auth';
    this.modal.set('');
    if (wasAuth && !this.user() && this.page() !== 'demo') this.navigate('demo');
  }
  @HostListener('document:keydown.escape') escape() {
    this.closeModal();
    this.closeSidebar(true);
  }
  @HostListener('document:keydown.tab', ['$event']) trapFocus(rawEvent: Event) {
    const event = rawEvent as KeyboardEvent;
    const scope = this.modal() ? '.modal' : this.sidebar() && innerWidth < 960 ? '.sidebar' : '';
    if (!scope) return;
    const elements = Array.from(
      document.querySelectorAll<HTMLElement>(
        `${scope} button:not(:disabled),${scope} input,${scope} select,${scope} textarea,${scope} a[href]`,
      ),
    ).filter((element) => element.getClientRects().length > 0);
    const first = elements[0],
      last = elements.at(-1);
    if (!first) return;
    if (!document.querySelector(scope)?.contains(document.activeElement)) {
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
      if (this.authMode === 'forgot') {
        await this.api.request('/auth/password-reset/request', 'POST', {
          email: this.form['email'],
        });
        this.authNotice.set(
          'Se esse e-mail estiver cadastrado, enviaremos um link. Confira também a caixa de spam.',
        );
        return;
      }
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
      this.setDetail(null);
      this.navigate('demo');
    });
  }
  completePasswordReset() {
    void this.action(async () => {
      await this.api.request<void>('/auth/password-reset/complete', 'POST', {
        token: this.resetToken(),
        newPassword: this.form['newPassword'],
        confirmPassword: this.form['confirmPassword'],
      });
      this.user.set(null);
      this.setDetail(null);
      this.form = { email: this.form['email'] || '' };
      this.resetToken.set('');
      this.authMode = 'login';
      this.authNotice.set('Senha atualizada. Entre com a nova senha.');
      this.modal.set('auth');
      this.navigate('demo');
      this.notify('Sua senha foi alterada.');
    });
  }
  createClub() {
    void this.action(async () => {
      const c = await this.api.request<Club>('/groups', 'POST', {
        name: this.form['name'],
        description: this.form['description'] || '',
        barbecueFrequency: this.form['barbecueFrequency'] || 'NONE',
        monthlyAmountCents: this.toCents(this.form['monthlyAmount']),
        billingDueDay: Number(this.form['billingDueDay'] || 10),
        occasionalAmountCents: this.toCents(this.form['occasionalAmount']),
        pixInstructions: this.form['pixInstructions'] || '',
        timeZone: this.form['timeZone'] || 'America/Sao_Paulo',
      });
      this.modal.set('');
      this.groupTab.set(c.barbecueFrequency === 'NONE' ? 'games' : 'barbecue');
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
      const timeZone = this.club()?.timeZone || 'America/Sao_Paulo';
      const d = await this.api.request<Detail>('/groups/' + this.club()!.id + '/games', 'POST', {
        title: this.form['title'],
        location: this.form['location'],
        startsAt: localDateTimeInZone(this.form['startsAt'], timeZone),
        teamCount: Number(this.form['teamCount']),
        teamSize: Number(this.form['teamSize']),
        chargeOccasional: !!this.form['chargeOccasional'],
        occasionalAmountCents: this.form['chargeOccasional']
          ? this.toCents(this.form['occasionalAmount'])
          : null,
        recurring: !!this.form['recurring'],
        recurrenceEndsOn: this.form['recurring'] ? this.form['recurrenceEndsOn'] || null : null,
      });
      this.setDetail(d);
      this.modal.set('');
      this.navigate('game/' + d.game.id);
      this.notify('Pelada marcada!');
    });
  }
  saveGame() {
    void this.action(async () => {
      const detail = this.detail()!;
      const updated = await this.api.request<Detail>('/games/' + detail.game.id, 'PUT', {
        title: this.form['title'],
        location: this.form['location'],
        startsAt: localDateTimeInZone(
          this.form['startsAt'],
          detail.club.timeZone || 'America/Sao_Paulo',
        ),
        scope: this.form['scope'] || 'ONE',
      });
      this.setDetail(updated);
      this.games.set(await this.api.request<Game[]>('/groups/' + detail.club.id + '/games'));
      this.modal.set('');
      this.notify('Pelada atualizada.');
    });
  }

  selectGroupTab(tab: string) {
    this.groupTab.set(tab);
    if (tab === 'finance') void this.loadFinance();
  }

  async loadFinance() {
    const club = this.club();
    if (!club) return;
    this.financeLoading.set(true);
    try {
      this.financeData.set(
        await this.api.request<FinanceSummary>(this.financeRequestPath(club.id)),
      );
    } catch (e) {
      this.showError(e);
    } finally {
      this.financeLoading.set(false);
    }
  }

  private financeRequestPath(clubId: string) {
    const params = new URLSearchParams({ period: this.financePeriod() });
    if (this.financePlayerFilter()) params.set('playerId', this.financePlayerFilter());
    if (this.financeGameFilter()) params.set('gameId', this.financeGameFilter());
    return `/groups/${clubId}/finance?${params.toString()}`;
  }

  updateFinanceFilters() {
    void this.loadFinance();
  }

  saveFinanceSettings() {
    const club = this.club();
    if (!club) return;
    void this.action(async () => {
      const summary = await this.api.request<FinanceSummary>(
        `/groups/${club.id}/finance/settings`,
        'PUT',
        {
          monthlyAmountCents: this.toCents(this.form['monthlyAmount']),
          billingDueDay: Number(this.form['billingDueDay']),
          occasionalAmountCents: this.toCents(this.form['occasionalAmount']),
          pixInstructions: this.form['pixInstructions'] || '',
        },
      );
      this.club.set({ ...club, ...summary.settings });
      this.modal.set('');
      await this.loadFinance();
      this.notify('Configuração financeira atualizada.');
    });
  }

  classifyMember(member: FinanceMember) {
    const club = this.club();
    if (!club) return;
    const monthly = member.billingType !== 'MONTHLY';
    void this.action(async () => {
      await this.api.request<FinanceSummary>(
        `/groups/${club.id}/finance/members/${member.playerId}`,
        'PUT',
        { monthly },
      );
      await this.loadFinance();
      this.notify(
        monthly
          ? `${member.playerName} passa a mensalista no próximo mês.`
          : `${member.playerName} passa a pagar por pelada no próximo mês.`,
      );
    });
  }

  uploadReceipt(charge: FinanceCharge, event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      this.showError(new Error('O comprovante deve ter no máximo 2 MB.'));
      return;
    }
    const club = this.club();
    if (!club) return;
    void this.action(async () => {
      await this.api.upload(`/finance/charges/${charge.id}/receipt`, file);
      await this.loadFinance();
      this.notify('Comprovante enviado para conferência.');
    });
  }

  openFinanceReview(charge: FinanceCharge, approve: boolean) {
    this.open('finance-review');
    this.form = { chargeId: charge.id, playerName: charge.playerName, approve, note: '' };
  }

  submitFinanceReview() {
    const approve = !!this.form['approve'];
    const path = `/finance/charges/${this.form['chargeId']}/${approve ? 'approve' : 'reject'}`;
    void this.action(async () => {
      await this.api.request(path, 'POST', approve ? undefined : { note: this.form['note'] || '' });
      this.modal.set('');
      await this.loadFinance();
      this.notify(approve ? 'Pagamento aprovado.' : 'Comprovante recusado.');
    });
  }

  openCashPayment(charge: FinanceCharge) {
    this.open('finance-cash');
    this.form = {
      chargeId: charge.id,
      playerName: charge.playerName,
      amountCents: charge.amountCents,
    };
  }

  markCashPayment() {
    void this.action(async () => {
      await this.api.request(`/finance/charges/${this.form['chargeId']}/cash`, 'POST');
      this.modal.set('');
      await this.loadFinance();
      this.notify('Pagamento em dinheiro registrado.');
    });
  }

  financeStatus(charge: FinanceCharge) {
    if (charge.status === 'PAID') return charge.manual ? 'Pago em dinheiro' : 'Pago';
    if (charge.status === 'CANCELLED') return 'Cancelada';
    if (charge.status === 'AWAITING_REVIEW') return 'Aguardando conferência';
    if (charge.status === 'REJECTED')
      return charge.overdue ? 'Vencida · comprovante recusado' : 'Comprovante recusado';
    return charge.overdue ? 'Vencida' : charge.dueSoon ? 'Vence em breve' : 'Pendente';
  }

  chargeType(charge: FinanceCharge) {
    return charge.type === 'MONTHLY'
      ? `Mensalidade · ${this.periodLabel(charge.period)}`
      : `Pelada avulsa · ${charge.gameTitle || 'Pelada'}`;
  }

  money(cents: number | null | undefined) {
    if (cents == null) return 'Não configurado';
    return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  private toAmountInput(cents: number | null) {
    return cents == null ? '' : (cents / 100).toFixed(2);
  }

  private toCents(value: unknown): number | null {
    if (value == null || String(value).trim() === '') return null;
    const amount = Number(String(value).replace(',', '.'));
    return Number.isFinite(amount) && amount > 0 ? Math.round(amount * 100) : null;
  }

  periodLabel(period: string | null) {
    if (!period) return '';
    const [year, month] = period.split('-').map(Number);
    return new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' }).format(
      new Date(year, month - 1, 1, 12),
    );
  }

  dueDateLabel(date: string) {
    return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short' }).format(
      new Date(`${date}T12:00:00`),
    );
  }

  whatsappReminder(charge: FinanceCharge) {
    const firstName = charge.playerName.split(' ')[0];
    const kind =
      charge.type === 'MONTHLY'
        ? `a mensalidade de ${this.periodLabel(charge.period)}`
        : `a pelada ${charge.gameTitle || ''}`;
    const message = `Oi, ${firstName}! Passando para lembrar de ${kind} do grupo ${this.club()?.name}. O vencimento é ${this.dueDateLabel(charge.dueDate)} e o valor é ${this.money(charge.amountCents)}. Se já pagou, pode enviar o comprovante pelo Tô Dentro. Obrigado!`;
    return 'https://wa.me/?text=' + encodeURIComponent(message);
  }

  memberBillingLabel(member: FinanceMember) {
    if (member.billingType !== 'MONTHLY') return 'Por pelada';
    return member.monthlyFrom
      ? `Mensal desde ${this.periodLabel(member.monthlyFrom.slice(0, 7))}`
      : 'Mensalista';
  }
  async createBarbecueSeries() {
    const club = this.club();
    if (!club) return;
    await this.action(async () => {
      const events = await this.api.request<Barbecue[]>(
        '/groups/' + club.id + '/barbecue-series',
        'POST',
        {
          startsAt: new Date(this.form['startsAt']).toISOString(),
          location: this.form['location'],
          timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Sao_Paulo',
        },
      );
      this.barbecues.set(events);
      this.club.set({ ...club, barbecueSeriesActive: true });
      this.modal.set('');
      this.notify('Churrasco recorrente configurado.');
    });
  }
  createBarbecue() {
    const club = this.club();
    if (!club) return;
    void this.action(async () => {
      const event = await this.api.request<Barbecue>('/groups/' + club.id + '/barbecues', 'POST', {
        startsAt: new Date(this.form['startsAt']).toISOString(),
        location: this.form['location'],
      });
      this.barbecues.update((events) => [...events, event]);
      this.modal.set('');
      this.notify('Churrasco marcado.');
    });
  }
  saveBarbecue() {
    const eventId = this.form['eventId'];
    void this.action(async () => {
      const event = await this.api.request<Barbecue>('/barbecues/' + eventId, 'PUT', {
        startsAt: new Date(this.form['startsAt']).toISOString(),
        location: this.form['location'],
      });
      this.updateBarbecue(event);
      this.modal.set('');
      this.notify('Edição do churrasco atualizada.');
    });
  }
  cancelBarbecue() {
    const eventId = this.form['eventId'];
    void this.action(async () => {
      const event = await this.api.request<Barbecue>('/barbecues/' + eventId + '/cancel', 'POST');
      this.updateBarbecue(event);
      this.modal.set('');
      this.notify('Churrasco cancelado.');
    });
  }
  toggleBarbecueAttendance(event: Barbecue) {
    void this.action(async () => {
      const updated = await this.api.request<Barbecue>(
        '/barbecues/' + event.id + '/attendance',
        event.attending ? 'DELETE' : 'POST',
      );
      this.updateBarbecue(updated);
      this.notify(updated.attending ? 'Presença confirmada no churrasco.' : 'Presença cancelada.');
    });
  }
  pauseBarbecueSeries(paused: boolean) {
    const club = this.club();
    if (!club) return;
    void this.action(async () => {
      const events = await this.api.request<Barbecue[]>(
        '/groups/' + club.id + '/barbecue-series/' + (paused ? 'pause' : 'resume'),
        'POST',
      );
      this.barbecues.set(events);
      this.club.set({ ...club, barbecueSeriesActive: !paused });
      this.notify(paused ? 'A recorrência foi pausada.' : 'A recorrência foi retomada.');
    });
  }
  inviteBarbecue(event: Barbecue) {
    void this.action(async () => {
      if (!event.inviteToken) return;
      const link = location.origin + location.pathname + '#barbecue-invite/' + event.inviteToken;
      try {
        await navigator.clipboard.writeText(link);
        this.notify('Convite do churrasco copiado.');
      } catch {
        this.form = { link };
        this.modal.set('share');
      }
    });
  }
  guestBarbecueAttendance(attending: boolean) {
    const token = location.hash.split('/')[1];
    void this.action(async () => {
      this.barbecueInvite.set(
        await this.api.request<Barbecue>(
          '/barbecue-invites/' + encodeURIComponent(token) + '/attendance',
          attending ? 'POST' : 'DELETE',
        ),
      );
      this.notify(attending ? 'Presença confirmada no churrasco.' : 'Presença cancelada.');
    });
  }
  updateBarbecue(event: Barbecue) {
    this.barbecues.update((events) => events.map((item) => (item.id === event.id ? event : item)));
  }
  editBarbecue(event: Barbecue) {
    this.open('edit-barbecue');
    this.form = {
      eventId: event.id,
      startsAt: this.localDateTime(event.startsAt),
      location: event.location,
    };
  }
  confirmCancelBarbecue(event: Barbecue) {
    this.open('cancel-barbecue');
    this.form = { eventId: event.id, location: event.location };
  }
  localDateTime(iso: string, timeZone = this.currentGroupTimeZone()) {
    return dateTimeForZone(iso, timeZone);
  }
  currentGroupTimeZone() {
    if (this.page() === 'group') return this.club()?.timeZone || 'America/Sao_Paulo';
    if (this.page() === 'game' || this.page() === 'demo')
      return this.detail()?.club.timeZone || 'America/Sao_Paulo';
    return this.club()?.timeZone || 'America/Sao_Paulo';
  }
  browserGroupTimeZone() {
    const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return this.groupTimeZones.some((zone) => zone.value === browserZone)
      ? browserZone
      : 'America/Sao_Paulo';
  }
  readonly groupTimeZones = [
    { value: 'America/Sao_Paulo', label: 'Horário de Brasília (UTC−03)' },
    { value: 'America/Manaus', label: 'Amazonas (UTC−04)' },
    { value: 'America/Rio_Branco', label: 'Acre (UTC−05)' },
    { value: 'America/Noronha', label: 'Fernando de Noronha (UTC−02)' },
  ];
  weekdayFor(value: string) {
    if (!value) return 'dia escolhido';
    const [year, month, day] = value.split('T')[0].split('-').map(Number);
    return new Intl.DateTimeFormat('pt-BR', { weekday: 'long' }).format(
      new Date(year, month - 1, day, 12),
    );
  }
  groupTimeZoneLabel(timeZone = this.currentGroupTimeZone()) {
    return this.groupTimeZones.find((zone) => zone.value === timeZone)?.label || timeZone;
  }
  editGame() {
    if (!this.owner() || !this.detail()?.game.editable) return;
    this.open('edit-game');
  }
  isFuture(iso: string) {
    return Date.parse(iso) > Date.now();
  }
  configureTeam() {
    void this.action(async () => {
      this.setDetail(
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
      this.setDetail(
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
      this.setDetail(
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
      const detail = this.detail()!;
      const scope = this.form['scope'] || 'ONE';
      this.setDetail(
        await this.api.request<Detail>(
          '/games/' + detail.game.id + '/cancel?scope=' + encodeURIComponent(scope),
          'POST',
        ),
      );
      this.games.set(await this.api.request<Game[]>('/groups/' + detail.club.id + '/games'));
      this.modal.set('');
      this.notify(
        scope === 'THIS_AND_FUTURE'
          ? 'Esta pelada e as próximas da série foram canceladas.'
          : 'Pelada cancelada.',
      );
    });
  }
  pick(player: Player) {
    void this.action(async () => {
      this.setDetail(
        await this.api.request<Detail>(this.teamPath() + '/players', 'POST', {
          playerId: player.id,
        }),
      );
      this.notify(player.name.split(' ')[0] + ' entrou no time.');
    });
  }
  release(player: Player) {
    void this.action(async () => {
      this.setDetail(
        await this.api.request<Detail>(this.teamPath() + '/players/' + player.id, 'DELETE'),
      );
      this.notify('Jogador disponível para outros times.');
    });
  }
  saveLineup(body: unknown) {
    void this.action(async () => {
      this.setDetail(await this.api.request<Detail>(this.teamPath() + '/lineup', 'PUT', body));
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
  date(iso: string, format: 'full' | 'day' | 'month' | 'hour' | 'weekday' = 'full') {
    const opts: Intl.DateTimeFormatOptions =
      format === 'full'
        ? { weekday: 'long', day: 'numeric', month: 'long' }
        : format === 'day'
          ? { day: '2-digit' }
          : format === 'month'
            ? { month: 'short' }
            : format === 'weekday'
              ? { weekday: 'long' }
              : { hour: '2-digit', minute: '2-digit' };
    return new Intl.DateTimeFormat('pt-BR', {
      ...opts,
      timeZone: this.currentGroupTimeZone(),
    }).format(new Date(iso));
  }
  eligibleCaptains() {
    return this.confirmed().filter((p) => !p.teamId || p.teamId === this.team()?.id);
  }
  clockText() {
    const seconds = this.elapsedSeconds();
    return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
  }
  score(teamId: string) {
    return this.detail()?.score.find((s) => s.teamId === teamId)?.goals || 0;
  }
  goalScorers() {
    return this.confirmed().filter(
      (p) =>
        p.teamId &&
        (this.form['ownGoal']
          ? p.teamId !== this.form['teamId']
          : p.teamId === this.form['teamId']),
    );
  }
  ownStars(playerId: string) {
    return this.detail()?.myRatings.find((r) => r.playerId === playerId)?.stars || 0;
  }
  canRate(player: Player) {
    return (
      this.ratingOpen() &&
      this.mine()?.status === 'CONFIRMED' &&
      !!this.mine()?.teamId &&
      player.teamId === this.mine()?.teamId &&
      player.id !== this.user()?.id
    );
  }
  rating(playerId: string) {
    return this.detail()?.ratings.find((r) => r.playerId === playerId);
  }
  average(value: number | null | undefined) {
    return value == null
      ? 'Sem nota'
      : value.toLocaleString('pt-BR', {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        });
  }
  matchAction(path: string, method = 'POST', body?: unknown, notice?: string) {
    void this.action(async () => {
      this.setDetail(
        await this.api.request<Detail>(
          '/games/' + this.detail()!.game.id + '/match/' + path,
          method,
          body,
        ),
      );
      this.modal.set('');
      if (notice) this.notify(notice);
    });
  }
  startMatch() {
    this.matchAction('start', 'POST', undefined, 'A bola está rolando!');
  }
  drawTeams() {
    const id = this.detail()?.game.id;
    if (!id) return;
    void this.action(async () => {
      this.setDetail(await this.api.request<Detail>('/games/' + id + '/teams/draw', 'POST'));
      this.modal.set('');
      this.notify('Times sorteados.');
    });
  }
  finishMatch() {
    this.matchAction(
      'finish',
      'POST',
      undefined,
      'Partida encerrada. As notas estão abertas por 24 horas.',
    );
  }
  correction(open: boolean) {
    this.matchAction(
      'correction',
      open ? 'POST' : 'DELETE',
      undefined,
      open ? 'Modo de correção aberto.' : 'Correções encerradas.',
    );
  }
  saveDuration() {
    this.matchAction(
      'duration',
      'PUT',
      { seconds: Number(this.form['seconds']) },
      'Duração corrigida.',
    );
  }
  addGoal() {
    const body: Record<string, unknown> = {
      teamId: this.form['teamId'],
      scorerId: this.form['scorerId'],
      ownGoal: !!this.form['ownGoal'],
    };
    if (this.detail()?.game.correctionOpen) body['minute'] = Number(this.form['minute']);
    this.matchAction('goals', 'POST', body, 'Gol registrado!');
  }
  voidGoal(id: string) {
    this.matchAction('goals/' + id, 'DELETE', undefined, 'Gol anulado.');
  }
  saveRating(playerId: string, stars: number) {
    void this.action(async () => {
      this.setDetail(
        await this.api.request<Detail>('/games/' + this.detail()!.game.id + '/ratings', 'PUT', {
          playerId,
          stars,
        }),
      );
      this.notify('Nota salva. Você pode alterá-la até o fim do prazo.');
    });
  }
  openProfile(player: Player) {
    if (this.demo()) return;
    void this.action(async () => {
      this.profile.set(await this.api.request<PlayerProfile>('/players/' + player.id + '/profile'));
      this.modal.set('profile');
    });
  }
}

import { CommonModule } from '@angular/common';
import {
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  signal,
  WritableSignal,
  computed,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Icon } from './icon';
import {
  GoalkeeperInvite,
  GoalkeeperInviteGroupOption,
  GoalkeeperProfile,
  GoalkeeperSearchResult,
  Municipality,
  MyGoalkeeperProfile,
  SkillLevel,
  SocialListing,
  SocialMatch,
  SocialMessage,
  SocialOwnedGroup,
  SocialSearchResult,
} from './models';

interface ListingDraft {
  categories: string[];
  courtName: string;
  neighborhood: string;
  description: string;
  skillLevel: string;
  preferredDays: string[];
  preferredPeriods: string[];
  published: boolean;
}

interface InviteDraft {
  senderClubId: string;
  startsAt: string;
  location: string;
  message: string;
}

interface ProposalDraft {
  startsAt: string;
  location: string;
}

interface GoalkeeperDraft {
  skillLevel: SkillLevel;
  preferredDays: string[];
  preferredPeriods: string[];
  description: string;
  published: boolean;
}

interface GoalkeeperInviteDraft {
  clubId: string;
  gameId: string;
  teamId: string;
  message: string;
}

type SocialView = 'search' | 'goalkeepers' | 'profiles' | 'inbox' | 'gk-invites' | 'gk-profile';

/** City autocomplete backed by the local municipal catalog. */
class CityPicker {
  query = signal('');
  options = signal<Municipality[]>([]);
  city = signal<Municipality | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

  constructor(
    private lookup: (query: string) => Promise<Municipality[]>,
    private changed: () => void = () => {},
  ) {}

  input(query: string) {
    this.query.set(query);
    const selected = this.options().find((city) => city.label === query);
    if (selected) {
      this.city.set(selected);
      return;
    }
    this.city.set(null);
    this.changed();
    clearTimeout(this.timer);
    if (query.trim().length < 2) {
      this.options.set([]);
      return;
    }
    this.timer = setTimeout(async () => {
      const cities = await this.lookup(query);
      if (query === this.query()) this.options.set(cities);
    }, 220);
  }

  choose(city: Municipality) {
    this.city.set(city);
    this.query.set(city.label);
    this.options.set([]);
    this.changed();
  }

  reset(city: Municipality | null) {
    this.city.set(city);
    this.query.set(city?.label ?? '');
    this.options.set([]);
  }

  dispose() {
    clearTimeout(this.timer);
  }
}

@Component({
  selector: 'app-social-page',
  standalone: true,
  imports: [CommonModule, FormsModule, Icon],
  templateUrl: './social-page.html',
})
export class SocialPage implements OnInit, OnDestroy {
  private api = inject(Api);
  private pollTimer?: ReturnType<typeof setInterval>;
  private noticeTimer?: ReturnType<typeof setTimeout>;
  private returnFocus: HTMLElement | null = null;

  busy = signal(false);
  loading = signal(true);
  error = signal('');
  notice = signal('');
  organizer = signal(false);
  view = signal<SocialView>('gk-profile');
  ownedGroups = signal<SocialOwnedGroup[]>([]);
  invitations = signal<SocialMatch[]>([]);
  results = signal<SocialSearchResult[]>([]);
  activeMatchId = signal('');
  messages = signal<SocialMessage[]>([]);
  messageText = '';
  activeMatch = computed(
    () => this.invitations().find((match) => match.id === this.activeMatchId()) ?? null,
  );
  unreadTotal = computed(() =>
    this.invitations().reduce((total, match) => total + match.unreadMessages, 0),
  );

  searchCity = new CityPicker(
    (query) => this.findCities(query),
    () => this.searched.set(false),
  );
  searchRadius = signal(50);
  searchCategory = signal('');
  searchLevel = signal('');
  searchDays = signal<string[]>([]);
  searchPeriods = signal<string[]>([]);
  searched = signal(false);

  selectedGroupId = signal('');
  selectedGroup = computed(
    () => this.ownedGroups().find((group) => group.clubId === this.selectedGroupId()) ?? null,
  );
  profileCity = new CityPicker((query) => this.findCities(query));
  draft: ListingDraft = this.emptyDraft();

  inviting = signal<SocialSearchResult | null>(null);
  inviteDraft: InviteDraft = this.emptyInvite();
  proposalDraft: ProposalDraft = { startsAt: '', location: '' };
  editingProposal = signal(false);

  // Goalkeeper profile of the signed-in player.
  goalkeeperProfile = signal<GoalkeeperProfile | null>(null);
  goalkeeperCity = new CityPicker((query) => this.findCities(query));
  goalkeeperDraft: GoalkeeperDraft = this.emptyGoalkeeperDraft();

  // Goalkeeper search, organizers only.
  keeperCity = new CityPicker(
    (query) => this.findCities(query),
    () => this.keeperSearched.set(false),
  );
  keeperRadius = signal(25);
  keeperLevel = signal('');
  keeperDays = signal<string[]>([]);
  keeperPeriods = signal<string[]>([]);
  keeperSearched = signal(false);
  keeperResults = signal<GoalkeeperSearchResult[]>([]);

  // Goalkeeper invites.
  receivedInvites = signal<GoalkeeperInvite[]>([]);
  sentInvites = signal<GoalkeeperInvite[]>([]);
  pendingReceived = computed(() => this.receivedInvites().filter((item) => item.canAccept).length);
  pendingSent = computed(() => this.sentInvites().filter((item) => item.canCancel).length);
  keeperInviting = signal<GoalkeeperSearchResult | null>(null);
  inviteOptions = signal<GoalkeeperInviteGroupOption[]>([]);
  optionsLoading = signal(false);
  keeperInviteDraft: GoalkeeperInviteDraft = this.emptyKeeperInvite();

  readonly categories = [
    { value: 'PICKUP', label: 'Pelada ou grupo aberto' },
    { value: 'FIXED_TEAM', label: 'Time fixo' },
  ];
  readonly days = [
    { value: 'MON', label: 'Seg' },
    { value: 'TUE', label: 'Ter' },
    { value: 'WED', label: 'Qua' },
    { value: 'THU', label: 'Qui' },
    { value: 'FRI', label: 'Sex' },
    { value: 'SAT', label: 'Sáb' },
    { value: 'SUN', label: 'Dom' },
  ];
  readonly periods = [
    { value: 'MORNING', label: 'Manhã' },
    { value: 'AFTERNOON', label: 'Tarde' },
    { value: 'EVENING', label: 'Noite' },
  ];
  readonly radii = [10, 25, 50, 100, 200];
  readonly levels: { value: SkillLevel; label: string }[] = [
    { value: 'RECREATIONAL', label: 'Recreativo' },
    { value: 'INTERMEDIATE', label: 'Intermediário' },
    { value: 'COMPETITIVE', label: 'Competitivo' },
  ];

  ngOnInit() {
    void this.load();
    this.pollTimer = setInterval(() => {
      if (document.hidden || this.busy()) return;
      void this.refreshKeeperInvites();
      if (!this.organizer()) return;
      void this.refreshInbox();
      if (this.activeMatch()?.chatOpen) void this.loadMessages(false);
    }, 15000);
  }

  ngOnDestroy() {
    clearInterval(this.pollTimer);
    clearTimeout(this.noticeTimer);
    [this.searchCity, this.profileCity, this.goalkeeperCity, this.keeperCity].forEach((picker) =>
      picker.dispose(),
    );
  }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(event: Event) {
    if (this.keeperInviting()) {
      event.preventDefault();
      this.closeKeeperInvite();
      return;
    }
    if (!this.inviting()) return;
    event.preventDefault();
    this.closeInvite();
  }

  private async load() {
    this.loading.set(true);
    try {
      const [mine, received] = await Promise.all([
        this.api.request<MyGoalkeeperProfile>('/social/goalkeeper-profile'),
        this.api.request<GoalkeeperInvite[]>('/social/goalkeeper-invites/received'),
      ]);
      this.organizer.set(mine.organizer);
      this.applyGoalkeeperProfile(mine.profile);
      this.receivedInvites.set(received);
      if (mine.organizer) {
        const [groups, matches, sent] = await Promise.all([
          this.api.request<SocialOwnedGroup[]>('/social/mine'),
          this.api.request<SocialMatch[]>('/social/invitations'),
          this.api.request<GoalkeeperInvite[]>('/social/goalkeeper-invites/sent'),
        ]);
        this.ownedGroups.set(groups);
        this.invitations.set(matches);
        this.sentInvites.set(sent);
        if (groups.length) this.selectGroup(groups[0].clubId);
      }
      this.view.set(
        mine.organizer
          ? 'search'
          : received.some((item) => item.canAccept)
            ? 'gk-invites'
            : 'gk-profile',
      );
    } catch (error) {
      this.showError(error);
    } finally {
      this.loading.set(false);
    }
  }

  private async refreshInbox() {
    try {
      const matches = await this.api.request<SocialMatch[]>('/social/invitations');
      this.invitations.set(matches);
      if (this.activeMatchId() && !matches.some((match) => match.id === this.activeMatchId())) {
        this.activeMatchId.set('');
        this.messages.set([]);
      }
    } catch {
      // Preserve the open page when a background refresh briefly loses connectivity.
    }
  }

  private async refreshKeeperInvites() {
    try {
      const [received, sent] = await Promise.all([
        this.api.request<GoalkeeperInvite[]>('/social/goalkeeper-invites/received'),
        this.organizer()
          ? this.api.request<GoalkeeperInvite[]>('/social/goalkeeper-invites/sent')
          : Promise.resolve([] as GoalkeeperInvite[]),
      ]);
      this.receivedInvites.set(received);
      this.sentInvites.set(sent);
    } catch {
      // Background refresh only; explicit actions report their own errors.
    }
  }

  selectView(view: SocialView) {
    this.view.set(view);
    this.error.set('');
    if (view === 'inbox') void this.refreshInbox();
    if (view === 'gk-invites') void this.refreshKeeperInvites();
  }

  selectGroup(clubId: string) {
    const group = this.ownedGroups().find((item) => item.clubId === clubId);
    if (!group) return;
    this.selectedGroupId.set(clubId);
    const listing = group.listing;
    this.draft = listing
      ? {
          categories: [...listing.categories],
          courtName: listing.courtName,
          neighborhood: listing.neighborhood,
          description: listing.description,
          skillLevel: listing.skillLevel,
          preferredDays: [...listing.preferredDays],
          preferredPeriods: [...listing.preferredPeriods],
          published: listing.published,
        }
      : this.emptyDraft();
    this.profileCity.reset(
      listing
        ? this.municipality(listing.municipalityCode, listing.municipalityName, listing.uf)
        : null,
    );
  }

  private async findCities(query: string) {
    try {
      return await this.api.request<Municipality[]>(
        `/social/cities?query=${encodeURIComponent(query)}`,
      );
    } catch (error) {
      this.showError(error);
      return [];
    }
  }

  toggle(list: WritableSignal<string[]>, value: string) {
    list.update((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  }

  toggleDraft(key: 'categories' | 'preferredDays' | 'preferredPeriods', value: string) {
    const current = this.draft[key];
    this.draft = {
      ...this.draft,
      [key]: current.includes(value)
        ? current.filter((item) => item !== value)
        : [...current, value],
    };
  }

  toggleGoalkeeperDraft(key: 'preferredDays' | 'preferredPeriods', value: string) {
    const current = this.goalkeeperDraft[key];
    this.goalkeeperDraft = {
      ...this.goalkeeperDraft,
      [key]: current.includes(value)
        ? current.filter((item) => item !== value)
        : [...current, value],
    };
  }

  async runSearch() {
    const city = this.searchCity.city();
    if (!city) {
      this.showError('Escolha uma cidade da lista antes de buscar.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.searched.set(true);
    try {
      const params = new URLSearchParams({
        cityCode: city.code,
        radiusKm: String(this.searchRadius()),
      });
      if (this.searchCategory()) params.set('category', this.searchCategory());
      if (this.searchLevel()) params.set('skillLevel', this.searchLevel());
      this.searchDays().forEach((day) => params.append('days', day));
      this.searchPeriods().forEach((period) => params.append('periods', period));
      this.results.set(
        await this.api.request<SocialSearchResult[]>(`/social/search?${params.toString()}`),
      );
    } catch (error) {
      this.showError(error);
    } finally {
      this.busy.set(false);
    }
  }

  saveListing() {
    const group = this.selectedGroup();
    const city = this.profileCity.city();
    if (!group || !city) {
      this.showError('Escolha a cidade da quadra pela lista de sugestões.');
      return;
    }
    this.runBusy(async () => {
      const saved = await this.api.request<SocialListing>(
        `/social/listings/${group.clubId}`,
        'PUT',
        {
          ...this.draft,
          municipalityCode: city.code,
        },
      );
      this.ownedGroups.update((groups) =>
        groups.map((item) => (item.clubId === group.clubId ? { ...item, listing: saved } : item)),
      );
      this.selectGroup(group.clubId);
      this.showNotice(
        saved.published ? 'Perfil publicado na Social.' : 'Perfil salvo e oculto da busca.',
      );
    });
  }

  removeListing() {
    const group = this.selectedGroup();
    if (!group?.listing) return;
    if (!window.confirm(`Retirar o perfil de ${group.clubName} da busca Social?`)) return;
    this.runBusy(async () => {
      await this.api.request<void>(`/social/listings/${group.clubId}`, 'DELETE');
      this.ownedGroups.update((groups) =>
        groups.map((item) => (item.clubId === group.clubId ? { ...item, listing: null } : item)),
      );
      this.selectGroup(group.clubId);
      this.showNotice('Perfil retirado da Social.');
    });
  }

  openInvite(result: SocialSearchResult) {
    const group = this.ownedGroups()[0];
    if (!group) {
      this.showError('Você precisa ser organizador de um grupo para enviar convites.');
      return;
    }
    this.inviteDraft = { senderClubId: group.clubId, startsAt: '', location: '', message: '' };
    this.inviting.set(result);
    this.error.set('');
  }

  closeInvite() {
    this.inviting.set(null);
    this.error.set('');
  }

  sendInvite() {
    const target = this.inviting();
    if (!target || !this.inviteDraft.startsAt || !this.inviteDraft.location.trim()) {
      this.showError('Preencha a data, o horário e o local propostos.');
      return;
    }
    this.runBusy(async () => {
      const invitation = await this.api.request<SocialMatch>('/social/invitations', 'POST', {
        senderClubId: this.inviteDraft.senderClubId,
        targetClubId: target.listing.clubId,
        startsAt: new Date(this.inviteDraft.startsAt).toISOString(),
        location: this.inviteDraft.location,
        message: this.inviteDraft.message,
      });
      this.inviting.set(null);
      await this.refreshInbox();
      this.invitations.update((matches) =>
        matches.some((item) => item.id === invitation.id) ? matches : [invitation, ...matches],
      );
      this.view.set('inbox');
      this.showNotice('Convite enviado ao organizador do time.');
    });
  }

  openMatch(match: SocialMatch) {
    this.activeMatchId.set(match.id);
    this.editingProposal.set(false);
    this.messages.set([]);
    if (match.chatOpen) {
      void this.loadMessages(true);
    }
  }

  async loadMessages(refreshReadState = true) {
    const id = this.activeMatchId();
    const match = this.activeMatch();
    if (!id || !match?.chatOpen) return;
    try {
      this.messages.set(
        await this.api.request<SocialMessage[]>(`/social/invitations/${id}/messages`),
      );
      if (refreshReadState) await this.refreshInbox();
    } catch (error) {
      if (refreshReadState) this.showError(error);
    }
  }

  respond(match: SocialMatch, response: 'accept' | 'decline' | 'confirm') {
    this.runBusy(async () => {
      const updated = await this.api.request<SocialMatch>(
        `/social/invitations/${match.id}/${response}`,
        'POST',
      );
      this.replaceMatch(updated);
      if (response === 'accept') {
        this.activeMatchId.set(match.id);
        await this.loadMessages(true);
      }
      this.showNotice(
        updated.status === 'EXPIRED'
          ? 'Este convite expirou e já não pode ser aceito.'
          : response === 'accept'
            ? 'Convite aceito. A conversa está aberta.'
            : response === 'confirm'
              ? 'Sua confirmação foi registrada.'
              : 'Convite recusado.',
      );
    });
  }

  sendMessage() {
    const match = this.activeMatch();
    const body = this.messageText.trim();
    if (!match || !body) return;
    this.runBusy(async () => {
      const message = await this.api.request<SocialMessage>(
        `/social/invitations/${match.id}/messages`,
        'POST',
        { body },
      );
      this.messages.update((messages) => [...messages, message]);
      this.messageText = '';
      await this.refreshInbox();
    });
  }

  startProposal(match: SocialMatch) {
    this.proposalDraft = {
      startsAt: this.localDateTime(match.proposedStartsAt),
      location: match.proposedLocation,
    };
    this.editingProposal.set(true);
  }

  submitProposal(match: SocialMatch) {
    if (!this.proposalDraft.startsAt || !this.proposalDraft.location.trim()) {
      this.showError('Preencha a nova data, o horário e o local.');
      return;
    }
    this.runBusy(async () => {
      const updated = await this.api.request<SocialMatch>(
        `/social/invitations/${match.id}/proposal`,
        'PUT',
        {
          startsAt: new Date(this.proposalDraft.startsAt).toISOString(),
          location: this.proposalDraft.location,
        },
      );
      this.replaceMatch(updated);
      this.editingProposal.set(false);
      await this.loadMessages(true);
      this.showNotice('Nova proposta enviada; o outro organizador precisa confirmar.');
    });
  }

  cancel(match: SocialMatch) {
    if (!window.confirm('Cancelar este convite ou amistoso para os dois grupos?')) return;
    this.runBusy(async () => {
      const updated = await this.api.request<SocialMatch>(
        `/social/invitations/${match.id}/cancel`,
        'POST',
      );
      this.replaceMatch(updated);
      this.showNotice('Convite ou amistoso cancelado para os dois grupos.');
    });
  }

  private replaceMatch(updated: SocialMatch) {
    this.invitations.update((matches) =>
      matches.map((match) => (match.id === updated.id ? updated : match)),
    );
  }

  // ---------- Goalkeeper profile ----------

  private applyGoalkeeperProfile(profile: GoalkeeperProfile | null) {
    this.goalkeeperProfile.set(profile);
    this.goalkeeperDraft = profile
      ? {
          skillLevel: profile.skillLevel,
          preferredDays: [...profile.preferredDays],
          preferredPeriods: [...profile.preferredPeriods],
          description: profile.description,
          published: profile.published,
        }
      : this.emptyGoalkeeperDraft();
    this.goalkeeperCity.reset(
      profile
        ? this.municipality(profile.municipalityCode, profile.municipalityName, profile.uf)
        : null,
    );
  }

  saveGoalkeeperProfile() {
    const city = this.goalkeeperCity.city();
    if (!city) {
      this.showError('Escolha sua cidade pela lista de sugestões.');
      return;
    }
    this.runBusy(async () => {
      const saved = await this.api.request<GoalkeeperProfile>('/social/goalkeeper-profile', 'PUT', {
        ...this.goalkeeperDraft,
        municipalityCode: city.code,
      });
      this.applyGoalkeeperProfile(saved);
      this.showNotice(
        saved.published
          ? 'Perfil de goleiro publicado. Organizadores próximos já podem encontrar você.'
          : 'Perfil de goleiro salvo e pausado. Ele não aparece nas buscas.',
      );
    });
  }

  removeGoalkeeperProfile() {
    if (!this.goalkeeperProfile()) return;
    if (!window.confirm('Excluir seu perfil de goleiro? Ele deixa de aparecer nas buscas.')) return;
    this.runBusy(async () => {
      await this.api.request<void>('/social/goalkeeper-profile', 'DELETE');
      this.applyGoalkeeperProfile(null);
      this.showNotice('Perfil de goleiro excluído.');
    });
  }

  // ---------- Goalkeeper search and invites ----------

  async runKeeperSearch() {
    const city = this.keeperCity.city();
    if (!city) {
      this.showError('Escolha uma cidade da lista antes de buscar.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.keeperSearched.set(true);
    try {
      const params = new URLSearchParams({
        cityCode: city.code,
        radiusKm: String(this.keeperRadius()),
      });
      if (this.keeperLevel()) params.set('skillLevel', this.keeperLevel());
      this.keeperDays().forEach((day) => params.append('days', day));
      this.keeperPeriods().forEach((period) => params.append('periods', period));
      this.keeperResults.set(
        await this.api.request<GoalkeeperSearchResult[]>(
          `/social/goalkeepers?${params.toString()}`,
        ),
      );
    } catch (error) {
      this.showError(error);
    } finally {
      this.busy.set(false);
    }
  }

  async openKeeperInvite(result: GoalkeeperSearchResult) {
    this.returnFocus = document.activeElement as HTMLElement | null;
    this.keeperInviteDraft = this.emptyKeeperInvite();
    this.keeperInviting.set(result);
    this.error.set('');
    this.optionsLoading.set(true);
    try {
      const options = await this.api.request<GoalkeeperInviteGroupOption[]>(
        '/social/goalkeeper-invites/options',
      );
      this.inviteOptions.set(options);
      const group = options.find((item) => item.games.length) ?? options[0];
      if (group) this.chooseInviteGroup(group.clubId);
    } catch (error) {
      this.showError(error);
    } finally {
      this.optionsLoading.set(false);
      requestAnimationFrame(() =>
        document
          .querySelector<HTMLElement>('.keeper-invite-dialog select, .keeper-invite-dialog button')
          ?.focus(),
      );
    }
  }

  closeKeeperInvite() {
    this.keeperInviting.set(null);
    this.error.set('');
    const target = this.returnFocus;
    this.returnFocus = null;
    requestAnimationFrame(() => target?.focus());
  }

  inviteGroup() {
    return (
      this.inviteOptions().find((item) => item.clubId === this.keeperInviteDraft.clubId) ?? null
    );
  }

  inviteGame() {
    return (
      this.inviteGroup()?.games.find((item) => item.gameId === this.keeperInviteDraft.gameId) ??
      null
    );
  }

  inviteTeam() {
    return (
      this.inviteGame()?.teams.find((item) => item.teamId === this.keeperInviteDraft.teamId) ?? null
    );
  }

  chooseInviteGroup(clubId: string) {
    const group = this.inviteOptions().find((item) => item.clubId === clubId);
    this.keeperInviteDraft = { ...this.keeperInviteDraft, clubId, gameId: '', teamId: '' };
    const game = group?.games.find((item) => item.teams.some((team) => team.available));
    if (game) this.chooseInviteGame(game.gameId);
  }

  chooseInviteGame(gameId: string) {
    const game = this.inviteGroup()?.games.find((item) => item.gameId === gameId);
    this.keeperInviteDraft = {
      ...this.keeperInviteDraft,
      gameId,
      teamId: game?.teams.find((team) => team.available)?.teamId ?? '',
    };
  }

  sendKeeperInvite() {
    const target = this.keeperInviting();
    const draft = this.keeperInviteDraft;
    if (!target || !draft.gameId || !draft.teamId) {
      this.showError('Escolha a pelada e um time com o gol livre.');
      return;
    }
    this.runBusy(async () => {
      const invite = await this.api.request<GoalkeeperInvite>(
        '/social/goalkeeper-invites',
        'POST',
        {
          profileId: target.profileId,
          gameId: draft.gameId,
          teamId: draft.teamId,
          message: draft.message,
        },
      );
      this.sentInvites.update((items) => [
        invite,
        ...items.filter((item) => item.id !== invite.id),
      ]);
      this.closeKeeperInvite();
      this.view.set('gk-invites');
      this.showNotice(
        `Convite enviado. O gol de ${invite.teamName} fica reservado para ${invite.goalkeeperName} até a resposta.`,
      );
    });
  }

  answerKeeperInvite(
    invite: GoalkeeperInvite,
    action: 'accept' | 'decline' | 'cancel' | 'withdraw',
  ) {
    if (
      action === 'withdraw' &&
      !window.confirm(
        'Sair desta partida? O gol fica livre para o organizador convidar outro goleiro.',
      )
    )
      return;
    if (action === 'cancel' && !window.confirm('Cancelar este convite e liberar o gol?')) return;
    this.runBusy(async () => {
      const updated = await this.api.request<GoalkeeperInvite>(
        `/social/goalkeeper-invites/${invite.id}/${action}`,
        'POST',
      );
      const replace = (items: GoalkeeperInvite[]) =>
        items.map((item) => (item.id === updated.id ? updated : item));
      this.receivedInvites.update(replace);
      this.sentInvites.update(replace);
      this.showNotice(this.answerNotice(action, updated));
    });
  }

  private answerNotice(action: string, invite: GoalkeeperInvite) {
    if (action === 'accept' && invite.status === 'ACCEPTED')
      return `Convite aceito. Você está no gol de ${invite.teamName}.`;
    if (action === 'decline') return 'Convite recusado. O gol foi liberado.';
    if (action === 'cancel') return 'Convite cancelado. O gol foi liberado.';
    if (action === 'withdraw') return 'Você saiu da partida e o gol foi liberado.';
    return invite.outcome || 'Este convite não aguarda mais resposta.';
  }

  keeperStatus(status: GoalkeeperInvite['status']) {
    const labels: Record<GoalkeeperInvite['status'], string> = {
      PENDING: 'Aguardando resposta',
      ACCEPTED: 'Aceito',
      DECLINED: 'Recusado',
      CANCELLED: 'Cancelado',
      EXPIRED: 'Expirado',
      WITHDRAWN: 'Goleiro saiu',
    };
    return labels[status];
  }

  rating(average: number | null, count: number) {
    if (average == null || !count) return 'Sem avaliações ainda';
    const value = average.toLocaleString('pt-BR', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    });
    return `${value} de 5 · ${count} ${count === 1 ? 'partida avaliada' : 'partidas avaliadas'}`;
  }

  gameDate(value: string, timeZone: string) {
    return new Intl.DateTimeFormat('pt-BR', {
      weekday: 'short',
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      timeZone,
    }).format(new Date(value));
  }

  matchOpponent(match: SocialMatch) {
    return match.outgoing ? match.guestClubName : match.hostClubName;
  }

  matchStatus(status: SocialMatch['status']) {
    const labels: Record<SocialMatch['status'], string> = {
      PENDING: 'Aguardando resposta',
      NEGOTIATING: 'Combinando detalhes',
      SCHEDULED: 'Amistoso confirmado',
      CHANGE_PENDING: 'Aguardando nova confirmação',
      DECLINED: 'Convite recusado',
      EXPIRED: 'Convite expirado',
      CANCELLED: 'Cancelado',
    };
    return labels[status];
  }

  skillLabel(value: string) {
    return this.levels.find((level) => level.value === value)?.label ?? value;
  }

  categoryLabel(value: string) {
    return this.categories.find((category) => category.value === value)?.label ?? value;
  }

  dayLabel(value: string) {
    return this.days.find((day) => day.value === value)?.label ?? value;
  }

  periodLabel(value: string) {
    return this.periods.find((period) => period.value === value)?.label ?? value;
  }

  formatDays(values: string[]) {
    return values.map((value) => this.dayLabel(value)).join(', ') || 'a combinar';
  }

  formatPeriods(values: string[]) {
    return values.map((value) => this.periodLabel(value)).join(', ') || 'a combinar';
  }

  formatDate(value: string | null) {
    return value
      ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(
          new Date(value),
        )
      : '—';
  }

  dateTimeMin() {
    return this.localDateTime(new Date().toISOString());
  }

  private localDateTime(value: string) {
    const date = new Date(value);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }

  private municipality(code: string, name: string, uf: string): Municipality {
    return { code, name, uf, label: `${name} — ${uf}` };
  }

  private emptyDraft(): ListingDraft {
    return {
      categories: ['PICKUP'],
      courtName: '',
      neighborhood: '',
      description: '',
      skillLevel: 'INTERMEDIATE',
      preferredDays: [],
      preferredPeriods: [],
      published: false,
    };
  }

  private emptyGoalkeeperDraft(): GoalkeeperDraft {
    return {
      skillLevel: 'INTERMEDIATE',
      preferredDays: [],
      preferredPeriods: [],
      description: '',
      published: true,
    };
  }

  private emptyInvite(): InviteDraft {
    return { senderClubId: '', startsAt: '', location: '', message: '' };
  }

  private emptyKeeperInvite(): GoalkeeperInviteDraft {
    return { clubId: '', gameId: '', teamId: '', message: '' };
  }

  private async runBusy(action: () => Promise<void>) {
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
    } catch (error) {
      this.showError(error);
    } finally {
      this.busy.set(false);
    }
  }

  private showError(error: unknown) {
    this.error.set(
      typeof error === 'string'
        ? error
        : error instanceof Error
          ? error.message
          : 'Não foi possível concluir. Tente novamente.',
    );
  }

  private showNotice(message: string) {
    this.notice.set(message);
    clearTimeout(this.noticeTimer);
    this.noticeTimer = setTimeout(() => this.notice.set(''), 4000);
  }
}

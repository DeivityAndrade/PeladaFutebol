import { CommonModule } from '@angular/common';
import {
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  inject,
  signal,
  computed,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Icon } from './icon';
import {
  Municipality,
  SocialListing,
  SocialMatch,
  SocialMessage,
  SocialOwnedGroup,
  SocialSchedule,
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

@Component({
  selector: 'app-social-page',
  standalone: true,
  imports: [CommonModule, FormsModule, Icon],
  templateUrl: './social-page.html',
})
export class SocialPage implements OnInit, OnDestroy {
  private api = inject(Api);
  private pollTimer?: ReturnType<typeof setInterval>;
  private searchCityTimer?: ReturnType<typeof setTimeout>;
  private profileCityTimer?: ReturnType<typeof setTimeout>;
  private noticeTimer?: ReturnType<typeof setTimeout>;

  busy = signal(false);
  loading = signal(true);
  error = signal('');
  notice = signal('');
  view = signal<'search' | 'profiles' | 'inbox'>('search');
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

  searchCityQuery = signal('');
  searchCityOptions = signal<Municipality[]>([]);
  searchCity = signal<Municipality | null>(null);
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
  profileCityQuery = signal('');
  profileCityOptions = signal<Municipality[]>([]);
  profileCity = signal<Municipality | null>(null);
  draft: ListingDraft = this.emptyDraft();

  inviting = signal<SocialSearchResult | null>(null);
  inviteDraft: InviteDraft = this.emptyInvite();
  proposalDraft: ProposalDraft = { startsAt: '', location: '' };
  editingProposal = signal(false);

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
  readonly levels = [
    { value: 'RECREATIONAL', label: 'Recreativo' },
    { value: 'INTERMEDIATE', label: 'Intermediário' },
    { value: 'COMPETITIVE', label: 'Competitivo' },
  ];

  ngOnInit() {
    void this.load();
    this.pollTimer = setInterval(() => {
      if (document.hidden || this.busy()) return;
      void this.refreshInbox();
      if (this.activeMatch()?.chatOpen) void this.loadMessages(false);
    }, 15000);
  }

  ngOnDestroy() {
    clearInterval(this.pollTimer);
    clearTimeout(this.searchCityTimer);
    clearTimeout(this.profileCityTimer);
    clearTimeout(this.noticeTimer);
  }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(event: Event) {
    if (!this.inviting()) return;
    event.preventDefault();
    this.closeInvite();
  }

  private async load() {
    this.loading.set(true);
    try {
      const [groups, matches] = await Promise.all([
        this.api.request<SocialOwnedGroup[]>('/social/mine'),
        this.api.request<SocialMatch[]>('/social/invitations'),
      ]);
      this.ownedGroups.set(groups);
      this.invitations.set(matches);
      if (groups.length) this.selectGroup(groups[0].clubId);
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

  selectView(view: 'search' | 'profiles' | 'inbox') {
    this.view.set(view);
    this.error.set('');
    if (view === 'inbox') void this.refreshInbox();
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
    if (listing) {
      const city: Municipality = {
        code: listing.municipalityCode,
        name: listing.municipalityName,
        uf: listing.uf,
        label: `${listing.municipalityName} — ${listing.uf}`,
      };
      this.profileCity.set(city);
      this.profileCityQuery.set(city.label);
    } else {
      this.profileCity.set(null);
      this.profileCityQuery.set('');
    }
    this.profileCityOptions.set([]);
  }

  onSearchCityInput(query: string) {
    this.searchCityQuery.set(query);
    const selected = this.searchCityOptions().find((city) => city.label === query);
    if (selected) {
      this.searchCity.set(selected);
      return;
    }
    this.searchCity.set(null);
    this.searched.set(false);
    clearTimeout(this.searchCityTimer);
    if (query.trim().length < 2) {
      this.searchCityOptions.set([]);
      return;
    }
    this.searchCityTimer = setTimeout(() => void this.findCities(query, 'search'), 220);
  }

  onProfileCityInput(query: string) {
    this.profileCityQuery.set(query);
    const selected = this.profileCityOptions().find((city) => city.label === query);
    if (selected) {
      this.profileCity.set(selected);
      return;
    }
    this.profileCity.set(null);
    clearTimeout(this.profileCityTimer);
    if (query.trim().length < 2) {
      this.profileCityOptions.set([]);
      return;
    }
    this.profileCityTimer = setTimeout(() => void this.findCities(query, 'profile'), 220);
  }

  chooseCity(city: Municipality, target: 'search' | 'profile') {
    if (target === 'search') {
      this.searchCity.set(city);
      this.searchCityQuery.set(city.label);
      this.searchCityOptions.set([]);
      this.searched.set(false);
    } else {
      this.profileCity.set(city);
      this.profileCityQuery.set(city.label);
      this.profileCityOptions.set([]);
    }
  }

  private async findCities(query: string, target: 'search' | 'profile') {
    try {
      const cities = await this.api.request<Municipality[]>(
        `/social/cities?query=${encodeURIComponent(query)}`,
      );
      if (target === 'search' && query === this.searchCityQuery())
        this.searchCityOptions.set(cities);
      if (target === 'profile' && query === this.profileCityQuery())
        this.profileCityOptions.set(cities);
    } catch (error) {
      this.showError(error);
    }
  }

  toggleSearchDay(value: string) {
    this.searchDays.update((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  }

  toggleSearchPeriod(value: string) {
    this.searchPeriods.update((current) =>
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

  async runSearch() {
    const city = this.searchCity();
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
    const city = this.profileCity();
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

  private emptyInvite(): InviteDraft {
    return { senderClubId: '', startsAt: '', location: '', message: '' };
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
      error instanceof Error ? error.message : 'Não foi possível concluir. Tente novamente.',
    );
  }

  private showNotice(message: string) {
    this.notice.set(message);
    clearTimeout(this.noticeTimer);
    this.noticeTimer = setTimeout(() => this.notice.set(''), 4000);
  }
}

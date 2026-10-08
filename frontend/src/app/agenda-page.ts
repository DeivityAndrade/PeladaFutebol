import {
  Component,
  computed,
  inject,
  input,
  OnDestroy,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Club, Game, SocialSchedule } from './models';
import { Icon } from './icon';
import { WhatsAppOperations } from './whatsapp-operations';

interface CalendarEvent {
  key: string;
  club: Club;
  title: string;
  startsAt: string;
  location: string;
  kind: 'game' | 'friendly';
  game?: Game;
  friendly?: SocialSchedule;
  relatedClubs?: Club[];
}
interface CalendarDay {
  key: string;
  number: number;
  inMonth: boolean;
  events: CalendarEvent[];
}

function dateKey(date: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);
  const get = (type: string) => parts.find((part) => part.type === type)!.value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}
// Date-only arithmetic uses UTC so DST never shifts a calendar cell into another day.
function calendarDate(key: string) {
  return new Date(key + 'T12:00:00Z');
}
function keyForCalendarDate(date: Date) {
  return date.toISOString().slice(0, 10);
}

@Component({
  selector: 'app-agenda-page',
  standalone: true,
  imports: [FormsModule, Icon, WhatsAppOperations],
  templateUrl: './agenda-page.html',
  styleUrl: './agenda-page.css',
})
export class AgendaPage implements OnInit, OnDestroy {
  private api = inject(Api);
  private disposed = false;
  private loadVersion = 0;
  private browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Sao_Paulo';
  clubs = input.required<Club[]>();
  userId = input.required<string>();
  navigate = output<string>();
  create = output<{ club: Club; date: string }>();
  events = signal<CalendarEvent[]>([]);
  loading = signal(true);
  failures = signal<string[]>([]);
  groupId = signal('');
  createClubId = signal('');
  clock = signal(Date.now());
  timeZone = computed(
    () => this.clubs().find((club) => club.id === this.groupId())?.timeZone || this.browserZone,
  );
  today = computed(() => dateKey(new Date(this.clock()), this.timeZone()));
  month = signal(dateKey(new Date(), this.browserZone).slice(0, 7));
  selectedDay = signal(dateKey(new Date(), this.browserZone));
  ownedClubs = computed(() => this.clubs().filter((club) => club.ownerId === this.userId()));
  createClub = computed(() => {
    const selected = this.clubs().find((club) => club.id === this.groupId());
    if (selected) return selected.ownerId === this.userId() ? selected : null;
    return (
      this.ownedClubs().find((club) => club.id === this.createClubId()) ||
      this.ownedClubs()[0] ||
      null
    );
  });
  filteredEvents = computed(() =>
    this.events()
      .filter(
        (event) =>
          !this.groupId() ||
          event.club.id === this.groupId() ||
          event.relatedClubs?.some((club) => club.id === this.groupId()),
      )
      .map((event) => {
        const selected = event.relatedClubs?.find((club) => club.id === this.groupId());
        if (!selected || selected.id === event.club.id) return event;
        return { ...event, club: selected, title: `Amistoso · ${event.club.name}` };
      }),
  );
  byDay = computed(() => {
    const days = new Map<string, CalendarEvent[]>();
    for (const event of this.filteredEvents()) {
      const key = dateKey(new Date(event.startsAt), this.timeZone());
      days.set(key, [...(days.get(key) || []), event]);
    }
    return days;
  });
  monthEvents = computed(() =>
    this.filteredEvents().filter((event) =>
      dateKey(new Date(event.startsAt), this.timeZone()).startsWith(this.month()),
    ),
  );
  scheduledCount = computed(
    () => this.monthEvents().filter((event) => !this.cancelled(event)).length,
  );
  selectedEvents = computed(() => this.byDay().get(this.selectedDay()) || []);
  nextEvent = computed(
    () =>
      this.filteredEvents().find(
        (event) =>
          !this.cancelled(event) &&
          (event.game?.matchStatus === 'LIVE' || Date.parse(event.startsAt) >= this.clock()),
      ) || null,
  );
  monthTitle = computed(() =>
    new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
      calendarDate(this.month() + '-01'),
    ),
  );
  selectedTitle = computed(() =>
    new Intl.DateTimeFormat('pt-BR', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      timeZone: 'UTC',
    }).format(calendarDate(this.selectedDay())),
  );
  days = computed<CalendarDay[]>(() => {
    const start = calendarDate(this.month() + '-01');
    const offset = (start.getUTCDay() + 6) % 7;
    const [year, month] = this.month().split('-').map(Number);
    const count = new Date(Date.UTC(year, month, 0)).getUTCDate();
    const cells = Math.ceil((offset + count) / 7) * 7;
    return Array.from({ length: cells }, (_, index) => {
      const day = new Date(start);
      day.setUTCDate(1 - offset + index);
      const key = keyForCalendarDate(day);
      return {
        key,
        number: day.getUTCDate(),
        inMonth: key.startsWith(this.month()),
        events: this.byDay().get(key) || [],
      };
    });
  });
  weeks = computed(() => {
    const days = this.days();
    return Array.from({ length: days.length / 7 }, (_, index) =>
      days.slice(index * 7, index * 7 + 7),
    );
  });

  async ngOnInit() {
    await this.reload(true);
  }
  ngOnDestroy() {
    this.disposed = true;
  }

  async reload(selectFirst = false) {
    if (!this.clubs().length) {
      this.loading.set(false);
      return;
    }
    const version = ++this.loadVersion;
    this.loading.set(true);
    const events: CalendarEvent[] = [];
    const failures: string[] = [];
    // Load independently: a failing group must not hide successfully loaded games.
    // Four requests at most at a time, even for users in many groups.
    for (let index = 0; index < this.clubs().length; index += 2) {
      const batch = this.clubs().slice(index, index + 2);
      const results = await Promise.allSettled(
        batch.flatMap((club) => [
          this.api.request<Game[]>(`/groups/${club.id}/games`),
          this.api.request<SocialSchedule[]>(`/groups/${club.id}/friendlies`),
        ]),
      );
      for (const [position, result] of results.entries()) {
        const club = batch[Math.floor(position / 2)];
        const kind = position % 2 === 0 ? 'game' : 'friendly';
        if (result.status === 'rejected') {
          failures.push(`${club.name} (${kind === 'game' ? 'peladas' : 'amistosos'})`);
          continue;
        }
        for (const item of result.value) {
          if (kind === 'game') {
            const game = item as Game;
            events.push({
              key: `game-${game.id}`,
              club,
              title: game.title,
              startsAt: game.startsAt,
              location: game.location,
              kind,
              game,
            });
          } else {
            const friendly = item as SocialSchedule;
            const shared = events.find((event) => event.friendly?.id === friendly.id);
            if (shared) {
              if (!shared.relatedClubs?.some((item) => item.id === club.id))
                shared.relatedClubs?.push(club);
              continue;
            }
            events.push({
              key: `friendly-${friendly.id}`,
              club,
              relatedClubs: [club],
              title: `Amistoso · ${friendly.opponentName}`,
              startsAt: friendly.startsAt,
              location: friendly.location,
              kind,
              friendly,
            });
          }
        }
      }
      if (this.disposed || version !== this.loadVersion) return;
    }
    const serverNow = events.find((event) => event.game?.serverNow)?.game?.serverNow;
    const now = serverNow ? Date.parse(serverNow) : Date.now();
    this.clock.set(Number.isFinite(now) ? now : Date.now());
    this.events.set(
      events.sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.key.localeCompare(b.key)),
    );
    this.failures.set(failures);
    if (selectFirst) {
      this.month.set(this.today().slice(0, 7));
      const nextDay = this.nextEvent()
        ? dateKey(new Date(this.nextEvent()!.startsAt), this.timeZone())
        : this.today();
      this.selectDay(nextDay.startsWith(this.month()) ? nextDay : this.today());
    }
    this.loading.set(false);
  }
  changeGroup(id: string) {
    this.groupId.set(id);
    this.selectDay(this.today());
  }
  selectDay(key: string, focus = false) {
    this.month.set(key.slice(0, 7));
    this.selectedDay.set(key);
    if (focus) requestAnimationFrame(() => document.getElementById('agenda-day-' + key)?.focus());
  }
  changeMonth(delta: number, focus = false) {
    const current = calendarDate(this.month() + '-01');
    current.setUTCMonth(current.getUTCMonth() + delta);
    const lastDay = new Date(
      Date.UTC(current.getUTCFullYear(), current.getUTCMonth() + 1, 0),
    ).getUTCDate();
    current.setUTCDate(Math.min(Number(this.selectedDay().slice(-2)), lastDay));
    this.selectDay(keyForCalendarDate(current), focus);
  }
  goToday() {
    this.clock.set(Date.now());
    this.selectDay(this.today());
  }
  goNext() {
    const next = this.nextEvent();
    if (next) this.selectDay(dateKey(new Date(next.startsAt), this.timeZone()));
  }
  moveDay(event: KeyboardEvent, day: CalendarDay) {
    const offsets: Record<string, number> = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -7,
      ArrowDown: 7,
    };
    if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault();
      this.changeMonth(event.key === 'PageUp' ? -1 : 1, true);
      return;
    }
    const weekday = (calendarDate(day.key).getUTCDay() + 6) % 7;
    const offset =
      event.key === 'Home' ? -weekday : event.key === 'End' ? 6 - weekday : offsets[event.key];
    if (offset === undefined) return;
    event.preventDefault();
    const next = calendarDate(day.key);
    next.setUTCDate(next.getUTCDate() + offset);
    this.selectDay(keyForCalendarDate(next), true);
  }
  dayLabel(day: CalendarDay) {
    const date = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full', timeZone: 'UTC' }).format(
      calendarDate(day.key),
    );
    const games = day.events
      .map(
        (event) => `${this.time(event)} ${event.title}, ${event.club.name}, ${this.status(event)}`,
      )
      .join('; ');
    return `${date}${day.key === this.today() ? ', hoje' : ''}. ${games || 'Sem jogos'}`;
  }
  time(event: CalendarEvent, groupZone = false) {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: groupZone ? event.club.timeZone || this.timeZone() : this.timeZone(),
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(event.startsAt));
  }
  eventDate(event: CalendarEvent) {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: this.timeZone(),
      day: '2-digit',
      month: 'short',
    }).format(new Date(event.startsAt));
  }
  cancelled(event: CalendarEvent) {
    return (
      !!event.game?.cancelled ||
      event.game?.matchStatus === 'CANCELLED' ||
      event.friendly?.status === 'CANCELLED'
    );
  }
  status(event: CalendarEvent) {
    if (this.cancelled(event)) return 'Cancelado';
    if (event.game?.matchStatus === 'LIVE') return 'Ao vivo';
    if (event.game?.matchStatus === 'FINISHED') return 'Finalizado';
    if (event.friendly?.status === 'CHANGE_PENDING') return 'Alteração pendente';
    if (Date.parse(event.startsAt) < this.clock()) return 'Data passada';
    return 'Agendado';
  }
  openEvent(event: CalendarEvent) {
    this.navigate.emit(event.game ? 'game/' + event.game.id : 'group/' + event.club.id);
  }
  markGame() {
    const club = this.createClub();
    if (club) this.create.emit({ club, date: this.selectedDay() });
  }
}

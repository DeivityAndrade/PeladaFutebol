export interface User {
  id: string;
  name: string;
  email: string;
}
export interface Club {
  id: string;
  name: string;
  description: string;
  ownerId: string;
  invite: string | null;
  memberCount: number;
  demo: boolean;
  barbecueFrequency: 'NONE' | 'MONTHLY' | 'EVERY_2_MONTHS' | 'EVERY_3_MONTHS';
  barbecueSeriesActive: boolean;
  monthlyAmountCents: number | null;
  billingDueDay: number;
  occasionalAmountCents: number | null;
  pixInstructions: string;
}
export interface Game {
  id: string;
  clubId: string;
  title: string;
  location: string;
  startsAt: string;
  teamCount: number;
  teamSize: number;
  confirmed: number;
  waiting: number;
  chargeOccasional: boolean;
  occasionalAmountCents: number | null;
  cancelled: boolean;
  editable: boolean;
  teamEditable: boolean;
  liveEnabled: boolean;
  matchStatus: 'SCHEDULED' | 'READY' | 'LIVE' | 'FINISHED' | 'CANCELLED' | 'LEGACY';
  matchStartedAt: string | null;
  matchEndedAt: string | null;
  matchDurationSeconds: number | null;
  correctionOpen: boolean;
  serverNow: string;
}
export interface Player {
  id: string;
  name: string;
  status: 'CONFIRMED' | 'WAITING';
  teamId: string | null;
  slot: number | null;
}
export interface Team {
  id: string;
  name: string;
  color: string;
  captainId: string | null;
  formation: string;
  version: number;
}
export interface Detail {
  game: Game;
  club: Club;
  attendees: Player[];
  teams: Team[];
  score: { teamId: string; goals: number }[];
  goals: Goal[];
  ratings: Rating[];
  myRatings: OwnRating[];
  ratingsVisibleAt: string | null;
}
export interface Goal {
  id: string;
  teamId: string;
  scorerId: string;
  scorerName: string;
  minute: number;
  ownGoal: boolean;
  voided: boolean;
}
export interface Rating {
  playerId: string;
  average: number | null;
  count: number;
}
export interface OwnRating {
  playerId: string;
  stars: number;
}
export interface PlayerProfile {
  id: string;
  name: string;
  average: number | null;
  ratedGames: number;
  history: {
    gameId: string;
    gameTitle: string;
    startsAt: string;
    average: number;
    count: number;
  }[];
}
export interface BarbecueAttendee {
  id: string;
  name: string;
}
export interface Barbecue {
  id: string;
  clubId: string;
  clubName: string;
  startsAt: string;
  location: string;
  cancelled: boolean;
  recurring: boolean;
  confirmed: number;
  attending: boolean;
  inviteToken: string | null;
  attendees: BarbecueAttendee[];
}
export interface FinanceMember {
  playerId: string;
  playerName: string;
  billingType: 'MONTHLY' | 'OCCASIONAL';
  monthlyFrom: string | null;
  monthlyThrough: string | null;
  monthlyAmountCents: number | null;
}
export interface FinanceCharge {
  id: string;
  playerId: string;
  playerName: string;
  type: 'MONTHLY' | 'GAME';
  period: string | null;
  gameId: string | null;
  gameTitle: string | null;
  gameStartsAt: string | null;
  amountCents: number;
  dueDate: string;
  status: 'PENDING' | 'AWAITING_REVIEW' | 'REJECTED' | 'PAID' | 'CANCELLED';
  overdue: boolean;
  dueSoon: boolean;
  manual: boolean;
  reviewNote: string | null;
  receiptAvailable: boolean;
  receiptFilename: string | null;
  receiptContentType: string | null;
  receiptUploadedAt: string | null;
  receiptExpiresAt: string | null;
  canUpload: boolean;
  canReview: boolean;
  canReadReceipt: boolean;
}
export interface FinanceSummary {
  settings: {
    monthlyAmountCents: number | null;
    billingDueDay: number;
    occasionalAmountCents: number | null;
    pixInstructions: string;
  };
  members: FinanceMember[];
  people: { id: string; name: string }[];
  charges: FinanceCharge[];
  receivedCents: number;
  pendingCents: number;
  overdueCents: number;
  overdueCount: number;
  dueSoonCount: number;
  canManage: boolean;
  canViewAll: boolean;
}
export const formations: Record<string, { x: number; y: number; label: string }[]> = {
  '2-2': [
    { x: 50, y: 15, label: 'GOL' },
    { x: 27, y: 43, label: 'FIXO E' },
    { x: 73, y: 43, label: 'FIXO D' },
    { x: 27, y: 69, label: 'ALA E' },
    { x: 73, y: 69, label: 'ALA D' },
  ],
  '1-2-1': [
    { x: 50, y: 15, label: 'GOL' },
    { x: 50, y: 38, label: 'FIXO' },
    { x: 23, y: 61, label: 'ALA E' },
    { x: 77, y: 61, label: 'ALA D' },
    { x: 50, y: 71, label: 'PIVÔ' },
  ],
  '3-1': [
    { x: 50, y: 15, label: 'GOL' },
    { x: 21, y: 43, label: 'ALA E' },
    { x: 50, y: 43, label: 'FIXO' },
    { x: 79, y: 43, label: 'ALA D' },
    { x: 50, y: 70, label: 'PIVÔ' },
  ],
};

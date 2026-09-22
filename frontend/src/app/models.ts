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
export const formations: Record<string, { x: number; y: number; label: string }[]> = {
  '2-2': [
    { x: 50, y: 85, label: 'GOL' },
    { x: 27, y: 57, label: 'FIXO E' },
    { x: 73, y: 57, label: 'FIXO D' },
    { x: 27, y: 24, label: 'ALA E' },
    { x: 73, y: 24, label: 'ALA D' },
  ],
  '1-2-1': [
    { x: 50, y: 85, label: 'GOL' },
    { x: 50, y: 62, label: 'FIXO' },
    { x: 23, y: 39, label: 'ALA E' },
    { x: 77, y: 39, label: 'ALA D' },
    { x: 50, y: 17, label: 'PIVÔ' },
  ],
  '3-1': [
    { x: 50, y: 85, label: 'GOL' },
    { x: 21, y: 57, label: 'ALA E' },
    { x: 50, y: 57, label: 'FIXO' },
    { x: 79, y: 57, label: 'ALA D' },
    { x: 50, y: 23, label: 'PIVÔ' },
  ],
};

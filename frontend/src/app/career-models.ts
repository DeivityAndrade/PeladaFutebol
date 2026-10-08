export interface CareerProgram {
  active: boolean;
  startedAt: string | null;
  canManage: boolean;
}
export interface CareerGroup {
  clubId: string;
  name: string;
  member: boolean;
}
export interface FigurinhaData {
  photoUrl?: string | null;
  playerId: string;
  name: string;
  title: string | null;
  frame: string | null;
  badges: string[];
  shared: boolean;
}
export interface Achievement {
  code: string;
  name: string;
  threshold: number;
  reward: string;
  awardedAt: string | null;
  unseen: boolean;
}
export interface CareerGame {
  gameId: string;
  title: string;
  startsAt: string;
}
export interface CareerData {
  clubId: string;
  clubName: string;
  timeZone: string;
  program: CareerProgram;
  canShare: boolean;
  card: FigurinhaData;
  appearances: number;
  achievements: Achievement[];
  history: CareerGame[];
  pending: CareerGame[];
  correctionUnseen: boolean;
}
export interface AttendancePerson {
  playerId: string;
  name: string;
  guestGoalkeeper: boolean;
  present: boolean | null;
}
export interface AttendanceReview {
  gameId: string;
  eligible: boolean;
  canReview: boolean;
  message: string;
  version: number;
  reviewedAt: string | null;
  players: AttendancePerson[];
  history: { version: number; organizerName: string; reviewedAt: string }[];
}
export const achievementNames: Record<string, string> = {
  FIRST_APPEARANCE: 'Tô dentro',
  FIVE_APPEARANCES: 'Já é de casa',
  TEN_APPEARANCES: 'Figurinha carimbada',
  TWENTY_FIVE_APPEARANCES: 'Parte da história',
};

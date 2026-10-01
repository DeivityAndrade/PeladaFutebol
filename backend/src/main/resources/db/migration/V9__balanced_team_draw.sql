ALTER TABLE members ADD COLUMN primary_position varchar(20),
  ADD COLUMN secondary_position varchar(20), ADD COLUMN skill_level integer;
ALTER TABLE members ADD CONSTRAINT member_skill CHECK (skill_level BETWEEN 1 AND 5),
  ADD CONSTRAINT member_primary_position CHECK (primary_position IN ('GOALKEEPER','DEFENSE','MIDFIELD','ATTACK','VERSATILE')),
  ADD CONSTRAINT member_secondary_position CHECK (secondary_position IN ('GOALKEEPER','DEFENSE','MIDFIELD','ATTACK','VERSATILE')),
  ADD CONSTRAINT member_classification_complete CHECK ((primary_position IS NULL AND skill_level IS NULL AND secondary_position IS NULL) OR (primary_position IS NOT NULL AND skill_level IS NOT NULL)),
  ADD CONSTRAINT member_positions_distinct CHECK (secondary_position IS NULL OR secondary_position <> primary_position);
CREATE TABLE draw_attempts (
  id uuid PRIMARY KEY, game_id uuid NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  organizer_id uuid NOT NULL REFERENCES players(id), fingerprint varchar(64) NOT NULL,
  mode varchar(10) NOT NULL CHECK (mode IN ('RANDOM','BALANCED')), snapshot text NOT NULL,
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, applied_at timestamptz
);
CREATE INDEX draw_game_history ON draw_attempts(game_id, applied_at);

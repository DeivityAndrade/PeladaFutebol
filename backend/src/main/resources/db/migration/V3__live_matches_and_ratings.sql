ALTER TABLE games ADD COLUMN match_started_at timestamptz;
ALTER TABLE games ADD COLUMN match_ended_at timestamptz;
ALTER TABLE games ADD COLUMN match_duration_seconds integer;
ALTER TABLE games ADD COLUMN correction_open boolean NOT NULL DEFAULT false;
ALTER TABLE games ADD COLUMN live_enabled boolean NOT NULL DEFAULT false;
UPDATE games SET live_enabled = true WHERE team_count = 2 AND starts_at > now();

CREATE TABLE goals (
    id uuid PRIMARY KEY,
    game_id uuid NOT NULL REFERENCES games,
    team_id uuid NOT NULL REFERENCES teams,
    scorer_id uuid NOT NULL REFERENCES players,
    minute integer NOT NULL CHECK (minute >= 0),
    own_goal boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    voided_at timestamptz
);
CREATE INDEX goals_game_idx ON goals (game_id, created_at);

CREATE TABLE ratings (
    id uuid PRIMARY KEY,
    game_id uuid NOT NULL REFERENCES games,
    rater_id uuid NOT NULL REFERENCES players,
    player_id uuid NOT NULL REFERENCES players,
    stars integer NOT NULL CHECK (stars BETWEEN 1 AND 5),
    updated_at timestamptz NOT NULL,
    UNIQUE (game_id, rater_id, player_id),
    CHECK (rater_id <> player_id)
);
CREATE INDEX ratings_game_player_idx ON ratings (game_id, player_id);

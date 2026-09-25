ALTER TABLE clubs
    ADD COLUMN time_zone varchar(80) NOT NULL DEFAULT 'America/Sao_Paulo';

CREATE TABLE password_reset_tokens (
    id uuid PRIMARY KEY,
    player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX password_reset_tokens_player_idx ON password_reset_tokens (player_id);
CREATE INDEX password_reset_tokens_expiry_idx ON password_reset_tokens (expires_at);

CREATE TABLE password_reset_limits (
    bucket_hash varchar(64) PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);

CREATE TABLE game_series (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL REFERENCES clubs,
    active boolean NOT NULL DEFAULT true,
    time_zone varchar(80) NOT NULL,
    anchor_starts_at timestamptz NOT NULL,
    anchor_occurrence_index integer NOT NULL DEFAULT 1 CHECK (anchor_occurrence_index > 0),
    next_occurrence_index integer NOT NULL DEFAULT 2 CHECK (next_occurrence_index > 0),
    ends_on date,
    title varchar(100) NOT NULL,
    location varchar(160) NOT NULL,
    team_count integer NOT NULL CHECK (team_count BETWEEN 2 AND 6),
    team_size integer NOT NULL CHECK (team_size BETWEEN 5 AND 12),
    charge_occasional boolean NOT NULL DEFAULT false,
    occasional_amount_cents bigint CHECK (occasional_amount_cents IS NULL OR occasional_amount_cents >= 0),
    created_at timestamptz NOT NULL,
    UNIQUE (id, club_id)
);

ALTER TABLE games
    ADD COLUMN series_id uuid,
    ADD COLUMN series_occurrence_index integer NOT NULL DEFAULT 0 CHECK (series_occurrence_index >= 0),
    ADD COLUMN series_exception boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT games_series_club_fk FOREIGN KEY (series_id, club_id) REFERENCES game_series (id, club_id),
    ADD CONSTRAINT games_series_occurrence_unique UNIQUE (series_id, series_occurrence_index);
CREATE INDEX game_series_club_active_idx ON game_series (club_id, active);

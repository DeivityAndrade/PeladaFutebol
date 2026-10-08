CREATE TABLE career_periods (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX career_one_open_period ON career_periods(club_id) WHERE ended_at IS NULL;
CREATE INDEX career_period_club ON career_periods(club_id, started_at);

CREATE TABLE attendance_reviews (
    id uuid PRIMARY KEY REFERENCES games ON DELETE CASCADE,
    eligible boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    reviewed_at timestamptz NOT NULL,
    reviewed_by uuid NOT NULL REFERENCES players
);
CREATE TABLE verified_attendance (
    id uuid PRIMARY KEY,
    game_id uuid NOT NULL REFERENCES attendance_reviews ON DELETE CASCADE,
    player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
    present boolean NOT NULL,
    UNIQUE (game_id, player_id)
);
CREATE INDEX verified_attendance_player ON verified_attendance(player_id, game_id);
CREATE TABLE attendance_audits (
    id uuid PRIMARY KEY,
    game_id uuid NOT NULL REFERENCES attendance_reviews ON DELETE CASCADE,
    version bigint NOT NULL,
    reviewed_by uuid NOT NULL REFERENCES players,
    created_at timestamptz NOT NULL,
    before_snapshot text NOT NULL,
    after_snapshot text NOT NULL,
    UNIQUE (game_id, version)
);
CREATE TABLE career_achievements (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
    code varchar(40) NOT NULL CHECK (code IN ('FIRST_APPEARANCE','FIVE_APPEARANCES','TEN_APPEARANCES','TWENTY_FIVE_APPEARANCES')),
    awarded_at timestamptz NOT NULL,
    seen_at timestamptz,
    UNIQUE (club_id, player_id, code)
);
CREATE TABLE career_cards (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
    shared boolean NOT NULL DEFAULT false,
    title varchar(40),
    frame varchar(40),
    badges varchar(160) NOT NULL DEFAULT '',
    observed_count integer NOT NULL DEFAULT 0,
    corrected_at timestamptz,
    correction_seen_at timestamptz,
    UNIQUE (club_id, player_id)
);

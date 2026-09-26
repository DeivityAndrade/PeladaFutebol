CREATE TABLE goalkeeper_profiles (
    id uuid PRIMARY KEY,
    player_id uuid NOT NULL UNIQUE REFERENCES players ON DELETE CASCADE,
    published boolean NOT NULL DEFAULT false,
    municipality_code varchar(7) NOT NULL CHECK (municipality_code ~ '^[0-9]{7}$'),
    skill_level varchar(20) NOT NULL CHECK (skill_level IN ('RECREATIONAL', 'INTERMEDIATE', 'COMPETITIVE')),
    preferred_days varchar(48) NOT NULL DEFAULT '',
    preferred_periods varchar(32) NOT NULL DEFAULT '',
    description varchar(300) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX goalkeeper_profiles_search_idx ON goalkeeper_profiles (published, municipality_code, skill_level);

CREATE TABLE goalkeeper_invites (
    id uuid PRIMARY KEY,
    organizer_id uuid NOT NULL REFERENCES players,
    goalkeeper_id uuid NOT NULL REFERENCES players,
    game_id uuid NOT NULL REFERENCES games,
    team_id uuid NOT NULL,
    message varchar(300) NOT NULL DEFAULT '',
    status varchar(12) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'WITHDRAWN')),
    status_reason varchar(20),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    responded_at timestamptz,
    closed_at timestamptz,
    FOREIGN KEY (team_id, game_id) REFERENCES teams (id, game_id),
    CHECK (organizer_id <> goalkeeper_id),
    CHECK (expires_at > created_at)
);
CREATE INDEX goalkeeper_invites_goalkeeper_idx ON goalkeeper_invites (goalkeeper_id, created_at DESC);
CREATE INDEX goalkeeper_invites_organizer_idx ON goalkeeper_invites (organizer_id, created_at DESC);
CREATE INDEX goalkeeper_invites_game_idx ON goalkeeper_invites (game_id, status);
-- One pending reservation per goal, and one live invitation per goalkeeper in the same game.
CREATE UNIQUE INDEX goalkeeper_invites_one_pending_goal_idx
    ON goalkeeper_invites (team_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX goalkeeper_invites_one_active_player_idx
    ON goalkeeper_invites (game_id, goalkeeper_id) WHERE status IN ('PENDING', 'ACCEPTED');

-- A guest goalkeeper plays one match through an accepted invite, without becoming a group member.
ALTER TABLE participations
    ADD COLUMN goalkeeper_invite_id uuid UNIQUE REFERENCES goalkeeper_invites;

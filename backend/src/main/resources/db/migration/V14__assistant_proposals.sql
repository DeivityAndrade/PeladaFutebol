CREATE TABLE assistant_proposals (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    club_id uuid NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    time_zone varchar(80) NOT NULL,
    draft text NOT NULL,
    question text,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    game_id uuid REFERENCES games(id) ON DELETE CASCADE
);
CREATE INDEX assistant_proposals_expiry ON assistant_proposals(expires_at);
CREATE TABLE assistant_request_limits (
    bucket varchar(100) PRIMARY KEY,
    requests integer NOT NULL,
    expires_at timestamptz NOT NULL
);

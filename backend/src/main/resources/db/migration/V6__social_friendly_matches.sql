CREATE TABLE social_listings (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL UNIQUE REFERENCES clubs ON DELETE CASCADE,
    published boolean NOT NULL DEFAULT false,
    categories varchar(24) NOT NULL,
    municipality_code varchar(7) NOT NULL CHECK (municipality_code ~ '^[0-9]{7}$'),
    court_name varchar(100) NOT NULL,
    neighborhood varchar(80) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    skill_level varchar(20) NOT NULL CHECK (skill_level IN ('RECREATIONAL', 'INTERMEDIATE', 'COMPETITIVE')),
    preferred_days varchar(48) NOT NULL DEFAULT '',
    preferred_periods varchar(32) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX social_listings_search_idx ON social_listings (published, municipality_code, skill_level);

CREATE TABLE social_matches (
    id uuid PRIMARY KEY,
    host_club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    guest_club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'NEGOTIATING', 'SCHEDULED', 'CHANGE_PENDING', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    proposed_starts_at timestamptz NOT NULL,
    proposed_location varchar(240) NOT NULL,
    starts_at timestamptz,
    location varchar(240),
    note varchar(500) NOT NULL DEFAULT '',
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    revision integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    host_confirmed_revision integer NOT NULL DEFAULT 0 CHECK (host_confirmed_revision >= 0),
    guest_confirmed_revision integer NOT NULL DEFAULT 0 CHECK (guest_confirmed_revision >= 0),
    host_last_read_at timestamptz,
    guest_last_read_at timestamptz,
    proposed_by uuid REFERENCES players,
    cancelled_by uuid REFERENCES players,
    cancelled_at timestamptz,
    CHECK (host_club_id <> guest_club_id),
    CHECK ((starts_at IS NULL AND location IS NULL) OR (starts_at IS NOT NULL AND location IS NOT NULL))
);
CREATE INDEX social_matches_host_idx ON social_matches (host_club_id, updated_at DESC);
CREATE INDEX social_matches_guest_idx ON social_matches (guest_club_id, updated_at DESC);
CREATE UNIQUE INDEX social_matches_one_open_pair_idx
    ON social_matches (LEAST(host_club_id, guest_club_id), GREATEST(host_club_id, guest_club_id))
    WHERE status IN ('PENDING', 'NEGOTIATING', 'CHANGE_PENDING');

CREATE TABLE social_messages (
    id uuid PRIMARY KEY,
    match_id uuid NOT NULL REFERENCES social_matches ON DELETE CASCADE,
    sender_id uuid NOT NULL REFERENCES players,
    body varchar(1000) NOT NULL CHECK (length(trim(body)) > 0),
    created_at timestamptz NOT NULL
);
CREATE INDEX social_messages_match_idx ON social_messages (match_id, created_at, id);

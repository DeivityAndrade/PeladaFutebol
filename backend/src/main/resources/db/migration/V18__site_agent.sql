CREATE TABLE assistant_conversations (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    club_id uuid REFERENCES clubs(id) ON DELETE CASCADE,
    proposal_id uuid REFERENCES assistant_proposals(id) ON DELETE SET NULL,
    history text NOT NULL DEFAULT '[]',
    scope text NOT NULL DEFAULT '[]',
    revision bigint NOT NULL DEFAULT 0,
    lease_id uuid,
    lease_until timestamptz,
    pending text,
    completed_id uuid,
    completed_result text,
    expires_at timestamptz NOT NULL
);
CREATE INDEX assistant_conversations_expiry ON assistant_conversations(expires_at);

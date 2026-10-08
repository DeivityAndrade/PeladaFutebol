CREATE TABLE whatsapp_contacts (
    player_id uuid PRIMARY KEY REFERENCES players ON DELETE CASCADE,
    phone varchar(16) UNIQUE,
    verified_at timestamptz,
    stopped_at timestamptz,
    code_hash varchar(64),
    challenge_id uuid,
    expires_at timestamptz,
    pending_phone varchar(16),
    issued_at timestamptz,
    window_start timestamptz,
    window_count integer NOT NULL DEFAULT 0,
    CHECK ((phone IS NULL) = (verified_at IS NULL))
);
CREATE UNIQUE INDEX whatsapp_code_idx ON whatsapp_contacts(code_hash) WHERE code_hash IS NOT NULL;
CREATE TABLE whatsapp_preferences (
    member_id uuid PRIMARY KEY REFERENCES members ON DELETE CASCADE,
    invitations boolean NOT NULL DEFAULT false,
    reminders boolean NOT NULL DEFAULT false,
    text_version varchar(30) NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE whatsapp_consent_events (
    id uuid PRIMARY KEY,
    player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
    club_id uuid NOT NULL REFERENCES clubs ON DELETE CASCADE,
    invitations boolean NOT NULL,
    reminders boolean NOT NULL,
    reason varchar(30) NOT NULL,
    text_version varchar(30) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX whatsapp_consent_retention_idx ON whatsapp_consent_events(created_at);
-- Persist only the hash of the provider message ID; never the message body.
CREATE TABLE whatsapp_received_events (
    event_hash varchar(64) PRIMARY KEY,
    received_at timestamptz NOT NULL
);
CREATE INDEX whatsapp_received_retention_idx ON whatsapp_received_events(received_at);

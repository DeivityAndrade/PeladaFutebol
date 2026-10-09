CREATE TABLE whatsapp_group_settings (
  club_id uuid PRIMARY KEY REFERENCES clubs ON DELETE CASCADE,
  enabled boolean NOT NULL DEFAULT false,
  reminder_minutes integer NOT NULL DEFAULT 120 CHECK (reminder_minutes BETWEEN 15 AND 1440)
);
CREATE TABLE whatsapp_inbox (
  id uuid PRIMARY KEY,
  event_hash varchar(64) NOT NULL UNIQUE,
  player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
  verified_at timestamptz NOT NULL,
  message_at timestamptz NOT NULL,
  text varchar(1200),
  button varchar(100),
  reply_to varchar(256),
  state varchar(20) NOT NULL DEFAULT 'PENDING',
  lease_id uuid,
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  result text,
  created_at timestamptz NOT NULL
);
CREATE INDEX whatsapp_inbox_pending_idx ON whatsapp_inbox(state, message_at);
CREATE TABLE whatsapp_conversations (
  player_id uuid PRIMARY KEY REFERENCES players ON DELETE CASCADE,
  verified_at timestamptz NOT NULL,
  last_received_at timestamptz NOT NULL,
  game_id uuid REFERENCES games ON DELETE SET NULL,
  club_id uuid REFERENCES clubs ON DELETE SET NULL,
  proposal_id uuid REFERENCES assistant_proposals ON DELETE SET NULL,
  proposal_version bigint,
  expires_at timestamptz NOT NULL
);
CREATE TABLE whatsapp_outbox (
  id uuid PRIMARY KEY,
  dedupe_key varchar(200) NOT NULL UNIQUE,
  player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
  club_id uuid REFERENCES clubs ON DELETE CASCADE,
  game_id uuid REFERENCES games ON DELETE CASCADE,
  verified_at timestamptz NOT NULL,
  kind varchar(20) NOT NULL CHECK (kind IN ('INVITATION','REMINDER','REPLY')),
  revision varchar(64),
  body text,
  state varchar(20) NOT NULL DEFAULT 'PENDING',
  send_after timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  lease_id uuid,
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  provider_id varchar(256) UNIQUE,
  error_code varchar(60),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX whatsapp_outbox_due_idx ON whatsapp_outbox(state, send_after);
CREATE TABLE whatsapp_actions (
  id uuid PRIMARY KEY,
  player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
  verified_at timestamptz NOT NULL,
  game_id uuid REFERENCES games ON DELETE CASCADE,
  proposal_id uuid REFERENCES assistant_proposals ON DELETE CASCADE,
  proposal_version bigint,
  revision varchar(64),
  action varchar(30) NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE TABLE whatsapp_responses (
  game_id uuid NOT NULL REFERENCES games ON DELETE CASCADE,
  player_id uuid NOT NULL REFERENCES players ON DELETE CASCADE,
  response varchar(20) NOT NULL CHECK (response IN ('GO','NO')),
  message_at timestamptz NOT NULL,
  PRIMARY KEY (game_id,player_id)
);
CREATE TABLE whatsapp_delivery_events (
  event_hash varchar(64) PRIMARY KEY,
  provider_id varchar(256) NOT NULL,
  state varchar(20) NOT NULL,
  message_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL
);
CREATE INDEX whatsapp_delivery_provider_idx ON whatsapp_delivery_events(provider_id);
CREATE TABLE whatsapp_worker_limits (
  bucket varchar(100) PRIMARY KEY,
  requests integer NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE TABLE whatsapp_game_revisions (
  game_id uuid PRIMARY KEY REFERENCES games ON DELETE CASCADE,
  revision varchar(64) NOT NULL,
  updated_at timestamptz NOT NULL
);

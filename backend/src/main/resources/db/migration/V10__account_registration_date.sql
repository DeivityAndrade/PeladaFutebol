-- Historical dates are unknown. Do not backfill existing accounts with today's date.
ALTER TABLE players ADD COLUMN created_at timestamptz;
CREATE INDEX players_created_at_idx ON players(created_at) WHERE created_at IS NOT NULL;

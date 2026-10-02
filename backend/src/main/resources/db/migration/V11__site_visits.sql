CREATE TABLE site_visit_days (
  day date PRIMARY KEY,
  visits bigint NOT NULL CHECK (visits > 0),
  first_recorded_at timestamptz NOT NULL
);

-- Anonymous identifiers exist only during the 30-minute deduplication window.
CREATE TABLE site_visit_windows (
  token_hash varchar(64) PRIMARY KEY,
  expires_at timestamptz NOT NULL
);
CREATE INDEX site_visit_windows_expiry ON site_visit_windows (expires_at);

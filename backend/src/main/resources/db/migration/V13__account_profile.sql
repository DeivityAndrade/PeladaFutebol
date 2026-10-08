ALTER TABLE players ADD COLUMN photo_version uuid;
CREATE TABLE player_photos (
    player_id uuid PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    data bytea NOT NULL
);

ALTER TABLE clubs
    ADD COLUMN monthly_amount_cents bigint CHECK (monthly_amount_cents IS NULL OR monthly_amount_cents > 0),
    ADD COLUMN billing_due_day integer NOT NULL DEFAULT 1 CHECK (billing_due_day BETWEEN 1 AND 31),
    ADD COLUMN occasional_amount_cents bigint CHECK (occasional_amount_cents IS NULL OR occasional_amount_cents > 0),
    ADD COLUMN pix_instructions varchar(500) NOT NULL DEFAULT '';

ALTER TABLE members
    ADD COLUMN billing_type varchar(12) NOT NULL DEFAULT 'OCCASIONAL'
        CHECK (billing_type IN ('MONTHLY', 'OCCASIONAL')),
    ADD COLUMN monthly_from date,
    ADD COLUMN monthly_through date,
    ADD COLUMN monthly_amount_cents bigint CHECK (monthly_amount_cents IS NULL OR monthly_amount_cents > 0),
    ADD CONSTRAINT members_monthly_range_check
        CHECK (monthly_from IS NULL OR monthly_through IS NULL OR monthly_through >= monthly_from);

ALTER TABLE games
    ADD COLUMN charge_occasional boolean NOT NULL DEFAULT false,
    ADD COLUMN occasional_amount_cents bigint CHECK (occasional_amount_cents IS NULL OR occasional_amount_cents > 0);

CREATE TABLE finance_charges (
    id uuid PRIMARY KEY,
    club_id uuid NOT NULL REFERENCES clubs,
    member_id uuid NOT NULL REFERENCES members,
    player_id uuid NOT NULL REFERENCES players,
    game_id uuid REFERENCES games,
    type varchar(12) NOT NULL CHECK (type IN ('MONTHLY', 'GAME')),
    period varchar(7),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    due_date date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'AWAITING_REVIEW', 'REJECTED', 'PAID', 'CANCELLED')),
    manual boolean NOT NULL DEFAULT false,
    review_note varchar(240),
    created_at timestamptz NOT NULL,
    payment_submitted_at timestamptz,
    paid_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid REFERENCES players,
    receipt_uploaded_at timestamptz,
    receipt_filename varchar(180),
    receipt_content_type varchar(100),
    receipt_deleted_at timestamptz,
    CHECK (
        (type = 'MONTHLY' AND period IS NOT NULL AND game_id IS NULL)
        OR (type = 'GAME' AND period IS NULL AND game_id IS NOT NULL)
    ),
    CHECK (period IS NULL OR period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE UNIQUE INDEX finance_monthly_charge_unique
    ON finance_charges (club_id, player_id, period) WHERE type = 'MONTHLY';
CREATE UNIQUE INDEX finance_game_charge_unique
    ON finance_charges (game_id, player_id) WHERE type = 'GAME';
CREATE INDEX finance_charges_group_due_idx ON finance_charges (club_id, due_date, status);
CREATE INDEX finance_charges_player_idx ON finance_charges (player_id, created_at DESC);

CREATE TABLE finance_receipt_files (
    charge_id uuid PRIMARY KEY REFERENCES finance_charges ON DELETE CASCADE,
    data bytea NOT NULL
);

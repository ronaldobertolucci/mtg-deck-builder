CREATE TABLE decks
(
    id         UUID PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(255) NOT NULL,
    format     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_decks_format CHECK (format IN ('STANDARD', 'MODERN', 'PIONEER', 'LEGACY', 'COMMANDER'))
);

CREATE INDEX idx_decks_user_id ON decks (user_id);

CREATE TABLE deck_cards
(
    id         UUID PRIMARY KEY,
    deck_id    UUID        NOT NULL,
    oracle_id  UUID        NOT NULL,
    quantity   INT         NOT NULL,
    board_type VARCHAR(20) NOT NULL,
    CONSTRAINT fk_deck_cards_deck FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE CASCADE,
    CONSTRAINT ck_deck_cards_quantity CHECK (quantity > 0),
    CONSTRAINT ck_deck_cards_board_type CHECK (board_type IN ('MAINBOARD', 'SIDEBOARD', 'COMMANDER')),
    CONSTRAINT uk_deck_cards_deck_oracle_board UNIQUE (deck_id, oracle_id, board_type)
);

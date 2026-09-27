ALTER TABLE deck_cards DROP CONSTRAINT ck_deck_cards_board_type;
ALTER TABLE deck_cards ADD CONSTRAINT ck_deck_cards_board_type
    CHECK (board_type IN ('MAINBOARD', 'COMMANDER', 'SIDEBOARD', 'COMPANION', 'TOKENS'));
ALTER TABLE deck_cards ADD COLUMN is_auto_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE deck_cards ADD CONSTRAINT ck_deck_cards_auto_generated
    CHECK (NOT is_auto_generated OR board_type = 'TOKENS');

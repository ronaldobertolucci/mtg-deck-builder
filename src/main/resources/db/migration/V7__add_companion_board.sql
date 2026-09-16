ALTER TABLE deck_cards DROP CONSTRAINT ck_deck_cards_board_type;
ALTER TABLE deck_cards ADD CONSTRAINT ck_deck_cards_board_type
    CHECK (board_type IN ('MAINBOARD', 'SIDEBOARD', 'COMMANDER', 'COMPANION'));
ALTER TABLE deck_cards ADD CONSTRAINT ck_deck_cards_companion_quantity
    CHECK (board_type <> 'COMPANION' OR quantity = 1);

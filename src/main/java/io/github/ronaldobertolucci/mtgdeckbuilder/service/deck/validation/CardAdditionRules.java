package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;

final class CardAdditionRules {
    private CardAdditionRules() {
    }

    static void validateInput(Deck deck, DeckCard card, CardDetailsResponse details) {
        if (card.getQuantity() <= 0) {
            throw new RuleViolationException("Card quantity must be positive");
        }
        if (card.getOracleId() == null || !card.getOracleId().equals(details.oracleId())) {
            throw new RuleViolationException("Card details must match the card being added");
        }
        if (card.getBoardType() == null) {
            throw new RuleViolationException("Card board type is required");
        }
        if (deck.getCards().contains(card)) {
            throw new RuleViolationException("Validate the card addition before adding it to the deck");
        }
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;

final class CompanionRules {
    private CompanionRules() {}

    static void validateAddition(Deck deck, DeckCard candidate, CardDetailsResponse details) {
        validateBoard(deck);
        if (candidate.getBoardType() != BoardType.COMPANION) return;
        if (candidate.getQuantity() != 1) {
            throw new RuleViolationException("A companion must have quantity 1");
        }
        if (!details.keywords().contains("Companion")) {
            throw new RuleViolationException("Only cards with the Companion keyword can occupy the companion zone");
        }
        if (deck.getCards().stream().anyMatch(card -> card.getBoardType() == BoardType.COMPANION)) {
            throw new RuleViolationException("A deck can have at most one companion");
        }
        // MVP: the individual companion's starting-deck condition is intentionally not evaluated.
    }

    static void validateBoard(Deck deck) {
        var companions = deck.getCards().stream().filter(card -> card.getBoardType() == BoardType.COMPANION).toList();
        if (companions.size() > 1 || companions.stream().anyMatch(card -> card.getQuantity() != 1)) {
            throw new RuleViolationException("A deck can have at most one companion with quantity 1");
        }
    }
}

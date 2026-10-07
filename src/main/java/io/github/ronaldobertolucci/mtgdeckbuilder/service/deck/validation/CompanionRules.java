package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;
import java.util.ArrayList;
import java.util.List;

final class CompanionRules {
    private CompanionRules() {}

    static void validateAddition(Deck deck, DeckCard candidate, CardDetailsResponse details) {
        validateBoard(deck);
        if (candidate.getBoardType() != BoardType.COMPANION) return;
        if (candidate.getQuantity() != 1) {
            throw new RuleViolationException(RuleErrorCode.INVALID_COMPANION_QUANTITY,
                    "A companion must have quantity 1", "quantity", List.of(candidate.getOracleId()));
        }
        if (!details.keywords().contains("Companion")) {
            throw new RuleViolationException(RuleErrorCode.COMPANION_NOT_ELIGIBLE,
                    "Only cards with the Companion keyword can occupy the companion zone",
                    "oracleId", List.of(candidate.getOracleId()));
        }
        if (deck.getCards().stream().anyMatch(card -> card.getBoardType() == BoardType.COMPANION)) {
            var ids = new ArrayList<>(deck.getCards().stream()
                    .filter(card -> card.getBoardType() == BoardType.COMPANION).map(DeckCard::getOracleId).toList());
            ids.add(candidate.getOracleId());
            throw new RuleViolationException(RuleErrorCode.COMPANION_LIMIT_EXCEEDED,
                    "A deck can have at most one companion", "boardType", ids);
        }
        // MVP: the individual companion's starting-deck condition is intentionally not evaluated.
    }

    static void validateBoard(Deck deck) {
        var companions = deck.getCards().stream().filter(card -> card.getBoardType() == BoardType.COMPANION).toList();
        if (companions.size() > 1) {
            throw new RuleViolationException(RuleErrorCode.COMPANION_LIMIT_EXCEEDED,
                    "A deck can have at most one companion with quantity 1", "boardType",
                    companions.stream().map(DeckCard::getOracleId).toList());
        }
        var invalidQuantities = companions.stream().filter(card -> card.getQuantity() != 1).toList();
        if (!invalidQuantities.isEmpty()) {
            throw new RuleViolationException(RuleErrorCode.INVALID_COMPANION_QUANTITY,
                    "A deck can have at most one companion with quantity 1", "quantity",
                    invalidQuantities.stream().map(DeckCard::getOracleId).toList());
        }
    }
}

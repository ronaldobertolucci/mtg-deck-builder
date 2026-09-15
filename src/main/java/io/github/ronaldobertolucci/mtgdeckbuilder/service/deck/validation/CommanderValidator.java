package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommanderValidator implements FormatValidatorStrategy {
    private final CardRuleOverrideService overrides;
    private final CardIntegrationService cardIntegration;

    public CommanderValidator(CardRuleOverrideService overrides, CardIntegrationService cardIntegration) {
        this.overrides = overrides;
        this.cardIntegration = cardIntegration;
    }

    @Override
    public boolean supports(Format format) {
        return format == Format.COMMANDER;
    }

    @Override
    public void validateCardAddition(Deck deck, DeckCard newCard, CardDetailsResponse cardDetails) {
        CardAdditionRules.validateInput(deck, newCard, cardDetails);
        if (!supports(deck.getFormat())) {
            throw new RuleViolationException("Unsupported commander format: " + deck.getFormat());
        }
        if (newCard.getBoardType() == BoardType.SIDEBOARD) {
            throw new RuleViolationException("Commander decks do not support a sideboard");
        }
        long mainboard = count(deck, BoardType.MAINBOARD)
                + (newCard.getBoardType() == BoardType.MAINBOARD ? newCard.getQuantity() : 0);
        long commanders = count(deck, BoardType.COMMANDER)
                + (newCard.getBoardType() == BoardType.COMMANDER ? newCard.getQuantity() : 0);
        if (commanders != 1) {
            throw new RuleViolationException("Commander decks require exactly one commander before adding mainboard cards");
        }
        if (mainboard > 99 || mainboard + commanders > 100) {
            throw new RuleViolationException("Commander decks cannot exceed 99 mainboard cards and 100 cards in total");
        }
        int limit = overrides.getMaxCopies(cardDetails, 1);
        long copies = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.MAINBOARD || card.getBoardType() == BoardType.COMMANDER)
                .filter(card -> card.getOracleId().equals(newCard.getOracleId()))
                .mapToLong(DeckCard::getQuantity).sum() + newCard.getQuantity();
        if (limit != Integer.MAX_VALUE && copies > limit) {
            throw new RuleViolationException("Copy limit exceeded for " + cardDetails.name() + ": " + limit);
        }
        if (newCard.getBoardType() == BoardType.COMMANDER) {
            // Validate existing cards too when defining a commander for an imported deck.
            for (DeckCard card : deck.getCards()) {
                if (card.getBoardType() == BoardType.MAINBOARD) {
                    validateColors(cardIntegration.fetchCardDetails(card.getOracleId()), cardDetails.colorIdentity());
                }
            }
        } else {
            DeckCard commander = deck.getCards().stream()
                    .filter(card -> card.getBoardType() == BoardType.COMMANDER).findFirst().orElseThrow();
            CardDetailsResponse commanderDetails = cardIntegration.fetchCardDetails(commander.getOracleId());
            validateColors(cardDetails, commanderDetails.colorIdentity());
        }
    }

    private long count(Deck deck, BoardType board) {
        return deck.getCards().stream().filter(card -> card.getBoardType() == board)
                .mapToLong(DeckCard::getQuantity).sum();
    }

    private void validateColors(CardDetailsResponse card, List<String> commanderColors) {
        if (!commanderColors.containsAll(card.colorIdentity())) {
            throw new RuleViolationException("Card color identity is outside the commander's color identity: " + card.name());
        }
    }
}

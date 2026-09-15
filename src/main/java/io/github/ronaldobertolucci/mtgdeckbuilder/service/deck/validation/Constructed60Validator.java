package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.springframework.stereotype.Component;

@Component
public class Constructed60Validator implements FormatValidatorStrategy {
    private final CardRuleOverrideService overrides;

    public Constructed60Validator(CardRuleOverrideService overrides) {
        this.overrides = overrides;
    }

    @Override
    public boolean supports(Format format) {
        return format == Format.STANDARD || format == Format.MODERN
                || format == Format.LEGACY || format == Format.PIONEER;
    }

    @Override
    public void validateCardAddition(Deck deck, DeckCard newCard, CardDetailsResponse cardDetails) {
        CardAdditionRules.validateInput(deck, newCard, cardDetails);
        if (!supports(deck.getFormat())) {
            throw new RuleViolationException("Unsupported constructed format: " + deck.getFormat());
        }
        if (newCard.getBoardType() == BoardType.COMMANDER) {
            throw new RuleViolationException("Constructed decks cannot have a commander");
        }
        long copies = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.MAINBOARD || card.getBoardType() == BoardType.SIDEBOARD)
                .filter(card -> card.getOracleId().equals(newCard.getOracleId()))
                .mapToLong(DeckCard::getQuantity).sum() + newCard.getQuantity();
        int limit = CardLegalityRules.enforce(cardDetails, deck.getFormat(), overrides.getMaxCopies(cardDetails));
        if (limit != Integer.MAX_VALUE && copies > limit) {
            throw new RuleViolationException("Copy limit exceeded for " + cardDetails.name() + ": " + limit);
        }
        long sideboard = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.SIDEBOARD)
                .mapToLong(DeckCard::getQuantity).sum();
        if (newCard.getBoardType() == BoardType.SIDEBOARD) {
            sideboard += newCard.getQuantity();
        }
        if (sideboard > 15) {
            throw new RuleViolationException("Sideboard cannot exceed 15 cards");
        }
    }
}

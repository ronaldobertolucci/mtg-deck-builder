package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.springframework.stereotype.Component;

import java.util.List;

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
            throw new RuleViolationException(RuleErrorCode.BOARD_TYPE_NOT_SUPPORTED,
                    "Constructed decks cannot have a commander", "boardType", List.of(newCard.getOracleId()));
        }
        if (newCard.getBoardType() == BoardType.TOKENS) {
            if (!io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.ResolvedCardResponse.isAccessory(cardDetails.layout(), cardDetails.typeLine()))
                throw new RuleViolationException(RuleErrorCode.CARD_NOT_ACCESSORY,
                        "Only accessories can be added to TOKENS", "oracleId", List.of(newCard.getOracleId()));
            return;
        }
        CompanionRules.validateAddition(deck, newCard, cardDetails);
        long copies = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.MAINBOARD || card.getBoardType() == BoardType.SIDEBOARD
                        || card.getBoardType() == BoardType.COMPANION)
                .filter(card -> card.getOracleId().equals(newCard.getOracleId()))
                .mapToLong(DeckCard::getQuantity).sum() + newCard.getQuantity();
        int limit = CardLegalityRules.enforce(cardDetails, deck.getFormat(), overrides.getMaxCopies(cardDetails));
        if (limit != Integer.MAX_VALUE && copies > limit) {
            throw new RuleViolationException(RuleErrorCode.COPY_LIMIT_EXCEEDED,
                    "Copy limit exceeded for this card (maximum: " + limit + ").",
                    "quantity", List.of(newCard.getOracleId()));
        }
        long sideboard = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.SIDEBOARD || card.getBoardType() == BoardType.COMPANION)
                .mapToLong(DeckCard::getQuantity).sum();
        if (newCard.getBoardType() == BoardType.SIDEBOARD || newCard.getBoardType() == BoardType.COMPANION) {
            sideboard += newCard.getQuantity();
        }
        if (sideboard > 15) {
            throw new RuleViolationException(RuleErrorCode.SIDEBOARD_SIZE_LIMIT_EXCEEDED,
                    "Sideboard and companion together cannot exceed 15 cards", "quantity", List.of(newCard.getOracleId()));
        }
    }
}

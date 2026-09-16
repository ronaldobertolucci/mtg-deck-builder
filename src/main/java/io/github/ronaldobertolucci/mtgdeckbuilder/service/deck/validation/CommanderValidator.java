package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

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
        CompanionRules.validateAddition(deck, newCard, cardDetails);
        long mainboard = count(deck, BoardType.MAINBOARD)
                + (newCard.getBoardType() == BoardType.MAINBOARD ? newCard.getQuantity() : 0);
        long commanders = count(deck, BoardType.COMMANDER)
                + (newCard.getBoardType() == BoardType.COMMANDER ? newCard.getQuantity() : 0);
        if (commanders < 1 || commanders > 2 || (newCard.getBoardType() == BoardType.COMMANDER && newCard.getQuantity() != 1)
                || deck.getCards().stream().anyMatch(card -> card.getBoardType() == BoardType.COMMANDER && card.getQuantity() != 1)) {
            throw new RuleViolationException("Commander decks require one or two distinct commanders, with one copy each");
        }
        if (mainboard + commanders > 100) {
            throw new RuleViolationException("Commander decks cannot exceed " + (100 - commanders)
                    + " mainboard cards and 100 cards in total");
        }
        int limit = CardLegalityRules.enforce(cardDetails, deck.getFormat(), overrides.getMaxCopies(cardDetails, 1));
        long copies = deck.getCards().stream()
                .filter(card -> card.getBoardType() == BoardType.MAINBOARD || card.getBoardType() == BoardType.COMMANDER
                        || card.getBoardType() == BoardType.COMPANION)
                .filter(card -> card.getOracleId().equals(newCard.getOracleId()))
                .mapToLong(DeckCard::getQuantity).sum() + newCard.getQuantity();
        if (limit != Integer.MAX_VALUE && copies > limit) {
            throw new RuleViolationException("Copy limit exceeded for " + cardDetails.name() + ": " + limit);
        }
        List<CardDetailsResponse> team = new ArrayList<>();
        for (DeckCard card : deck.getCards()) {
            if (card.getBoardType() == BoardType.COMMANDER) {
                team.add(cardIntegration.fetchCardDetails(card.getOracleId()));
            }
        }
        if (newCard.getBoardType() == BoardType.COMMANDER) team.add(cardDetails);
        for (var commander : team) CardLegalityRules.enforce(commander, Format.COMMANDER, 1);
        CommanderPairRules.validate(team);
        Set<String> colors = new HashSet<>();
        team.forEach(commander -> colors.addAll(commander.colorIdentity()));
        if (newCard.getBoardType() == BoardType.COMMANDER) {
            for (DeckCard card : deck.getCards()) {
                if (card.getBoardType() == BoardType.MAINBOARD || card.getBoardType() == BoardType.COMPANION) {
                    validateColors(cardIntegration.fetchCardDetails(card.getOracleId()), colors);
                }
            }
        } else {
            validateColors(cardDetails, colors);
        }
    }

    /** Checks the final card count; additions still allow a deck under construction. */
    public void validateDeckCompletion(Deck deck) {
        CompanionRules.validateBoard(deck);
        if (deck.getFormat() != Format.COMMANDER || count(deck, BoardType.COMMANDER) < 1
                || count(deck, BoardType.COMMANDER) > 2 || count(deck, BoardType.SIDEBOARD) != 0
                || count(deck, BoardType.MAINBOARD) + count(deck, BoardType.COMMANDER) != 100) {
            throw new RuleViolationException("A completed Commander deck must contain exactly 100 cards including its commanders");
        }
    }

    private long count(Deck deck, BoardType board) {
        return deck.getCards().stream().filter(card -> card.getBoardType() == board)
                .mapToLong(DeckCard::getQuantity).sum();
    }

    private void validateColors(CardDetailsResponse card, Set<String> commanderColors) {
        if (!commanderColors.containsAll(card.colorIdentity())) {
            throw new RuleViolationException("Card color identity is outside the commander's color identity: " + card.name());
        }
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.FormatValidatorStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DeckService {
    private final DeckRepository repository;
    private final CardIntegrationService integration;
    private final List<FormatValidatorStrategy> strategies;

    public DeckService(DeckRepository repository, CardIntegrationService integration,
                       List<FormatValidatorStrategy> strategies) {
        this.repository = repository;
        this.integration = integration;
        this.strategies = strategies;
    }

    public DeckResponse create(Long userId, CreateDeckRequest request) {
        Deck deck = new Deck(userId, request.name(), request.format());
        if (request.format() == Format.COMMANDER && request.commanderOracleId() == null) {
            throw new RuleViolationException("Commander oracle ID is required");
        }
        if (request.commanderOracleId() != null) {
            var card = new DeckCard(request.commanderOracleId(), 1, BoardType.COMMANDER);
            validator(deck).validateCardAddition(deck, card, integration.fetchCardDetails(card.getOracleId()));
            deck.addCard(card);
        }
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    public DeckResponse upsertCard(Long userId, UUID deckId, UpsertDeckCardRequest request) {
        // Serialize modifications of a deck so concurrent requests cannot bypass its limits.
        Deck deck = repository.findOwnedForUpdate(deckId, userId).orElseThrow(DeckNotFoundException::new);
        DeckCard existing = deck.getCards().stream()
                .filter(card -> card.getOracleId().equals(request.oracleId()) && card.getBoardType() == request.boardType())
                .findFirst().orElse(null);
        if (request.quantity() == 0) {
            if (existing != null) {
                if (existing.getBoardType() == BoardType.COMMANDER && deck.getCards().stream()
                        .anyMatch(card -> card.getBoardType() == BoardType.MAINBOARD)) {
                    throw new RuleViolationException("Remove mainboard cards before removing the commander");
                }
                deck.removeCard(existing);
            }
        } else {
            var details = integration.fetchCardDetails(request.oracleId());
            var candidate = new DeckCard(request.oracleId(), request.quantity(), request.boardType());
            // Validate a detached view excluding the replaced row, without mutating managed entities.
            Deck validationDeck = new Deck(deck.getUserId(), deck.getName(), deck.getFormat());
            for (DeckCard card : deck.getCards()) {
                if (card != existing) validationDeck.addCard(new DeckCard(
                        card.getOracleId(), card.getQuantity(), card.getBoardType()));
            }
            validator(deck).validateCardAddition(validationDeck, candidate, details);
            if (existing == null) deck.addCard(candidate);
            else existing.setQuantity(request.quantity());
        }
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    private FormatValidatorStrategy validator(Deck deck) {
        return strategies.stream().filter(strategy -> strategy.supports(deck.getFormat())).findFirst()
                .orElseThrow(() -> new RuleViolationException("Unsupported format: " + deck.getFormat()));
    }
}

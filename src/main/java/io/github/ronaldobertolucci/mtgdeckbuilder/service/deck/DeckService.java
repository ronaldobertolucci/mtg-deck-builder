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
        var ids = request.commanderOracleIds() == null ? List.<UUID>of() : request.commanderOracleIds();
        if (ids.size() > 2 || ids.stream().anyMatch(java.util.Objects::isNull)
                || ids.stream().distinct().count() != ids.size()
                || (request.format() == Format.COMMANDER && ids.isEmpty())
                || (request.format() != Format.COMMANDER && !ids.isEmpty())) {
            throw new RuleViolationException("Invalid commander selection");
        }
        for (UUID id : ids) deck.addCard(new DeckCard(id, 1, BoardType.COMMANDER));
        validateCommanders(deck);
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
                if (existing.getBoardType() == BoardType.COMMANDER) {
                    Deck remaining = without(deck, existing);
                    if (remaining.getCards().stream().noneMatch(card -> card.getBoardType() == BoardType.COMMANDER)
                            && !remaining.getCards().isEmpty()) {
                        throw new RuleViolationException("Remove mainboard and companion cards before removing the last commander");
                    }
                    validateCommanders(remaining);
                }
                deck.removeCard(existing);
            }
        } else {
            var details = integration.fetchCardDetails(request.oracleId());
            var candidate = new DeckCard(request.oracleId(), request.quantity(), request.boardType());
            // Validate a detached view excluding the replaced row, without mutating managed entities.
            Deck validationDeck = without(deck, existing);
            validator(deck).validateCardAddition(validationDeck, candidate, details);
            if (existing == null) deck.addCard(candidate);
            else existing.setQuantity(request.quantity());
        }
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    private void validateCommanders(Deck deck) {
        for (DeckCard card : deck.getCards()) {
            if (card.getBoardType() == BoardType.COMMANDER) {
                validator(deck).validateCardAddition(without(deck, card),
                        new DeckCard(card.getOracleId(), 1, BoardType.COMMANDER),
                        integration.fetchCardDetails(card.getOracleId()));
            }
        }
    }

    private Deck without(Deck deck, DeckCard excluded) {
        Deck copy = new Deck(deck.getUserId(), deck.getName(), deck.getFormat());
        for (DeckCard card : deck.getCards()) {
            if (card != excluded) copy.addCard(new DeckCard(card.getOracleId(), card.getQuantity(), card.getBoardType()));
        }
        return copy;
    }

    private FormatValidatorStrategy validator(Deck deck) {
        return strategies.stream().filter(strategy -> strategy.supports(deck.getFormat())).findFirst()
                .orElseThrow(() -> new RuleViolationException("Unsupported format: " + deck.getFormat()));
    }
}

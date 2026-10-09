package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.FormatValidatorStrategy;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;

@Service
@Transactional
public class DeckService {
    private final DeckRepository repository;
    private final CardIntegrationService integration;
    private final List<FormatValidatorStrategy> strategies;
    private final DeckImportParserService parser;
    private final DeckAccessoryService accessories;

    public DeckService(DeckRepository repository, CardIntegrationService integration,
                       List<FormatValidatorStrategy> strategies, DeckImportParserService parser) {
        this.repository = repository;
        this.integration = integration;
        this.strategies = strategies;
        this.parser = parser;
        this.accessories = new DeckAccessoryService(integration);
    }

    @Transactional(readOnly = true)
    public Page<DeckSummaryResponse> list(Long userId, int page, int size) {
        return repository.findByUserId(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "id")))
                .map(DeckSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public DeckResponse get(Long userId, UUID deckId) {
        return DeckResponse.from(repository.findByIdAndUserId(deckId, userId)
                .orElseThrow(DeckNotFoundException::new));
    }

    public DeckResponse rename(Long userId, UUID deckId, RenameDeckRequest request) {
        Deck deck = repository.findOwnedForUpdate(deckId, userId).orElseThrow(DeckNotFoundException::new);
        deck.setName(request.name());
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    public void delete(Long userId, UUID deckId) {
        Deck deck = repository.findOwnedForUpdate(deckId, userId).orElseThrow(DeckNotFoundException::new);
        repository.delete(deck);
    }

    @Transactional
    public DeckResponse importDeck(Long userId, ImportDeckRequest request) {
        var cards = parser.parse(request.rawText());
        Deck deck = repository.saveAndFlush(new Deck(userId, request.name(), request.format()));
        var detailsById = new HashMap<UUID, CardDetailsResponse>();
        for (ParsedDeckCard card : cards) {
            var details = integration.fetchCardDetailsByName(card.name());
            detailsById.put(details.oracleId(), details);
            DeckCard existing = deck.getCards().stream()
                    .filter(value -> value.getOracleId().equals(details.oracleId())
                            && value.getBoardType() == card.boardType())
                    .findFirst().orElse(null);
            long total = (long) card.quantity() + (existing == null ? 0 : existing.getQuantity());
            if (total > Integer.MAX_VALUE) throw new RuleViolationException("Card quantity is too large: " + card.name());
            DeckCard candidate = new DeckCard(details.oracleId(), (int) total, card.boardType());
            if (existing == null) deck.addCard(candidate);
            else existing.setQuantity(candidate.getQuantity());
        }
        // Validate against the complete import so commander pairs and colors are independent of text order.
        for (DeckCard card : deck.getCards()) {
            validator(deck).validateCardAddition(without(deck, card),
                    new DeckCard(card.getOracleId(), card.getQuantity(), card.getBoardType()),
                    detailsById.get(card.getOracleId()));
        }
        accessories.addAccessories(deck, deck.getCards().stream()
                .filter(card -> DeckAccessoryService.generatesAccessories(card.getBoardType()))
                .map(card -> detailsById.get(card.getOracleId())).distinct().toList());
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    public DeckResponse create(Long userId, CreateDeckRequest request) {
        Deck deck = new Deck(userId, request.name(), request.format());
        var ids = request.commanderOracleIds() == null ? List.<UUID>of() : request.commanderOracleIds();
        if (ids.size() > 2 || ids.stream().anyMatch(java.util.Objects::isNull)
                || ids.stream().distinct().count() != ids.size()
                || (request.format() == Format.COMMANDER && ids.isEmpty())
                || (request.format() != Format.COMMANDER && !ids.isEmpty())) {
            throw new RuleViolationException(RuleErrorCode.INVALID_COMMANDER_SELECTION, "Invalid commander selection", "commanderOracleIds", ids);
        }
        for (UUID id : ids) deck.addCard(new DeckCard(id, 1, BoardType.COMMANDER));
        accessories.addAccessories(deck, validateCommanders(deck));
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
                            && remaining.getCards().stream().anyMatch(card -> card.getBoardType() == BoardType.MAINBOARD
                                    || card.getBoardType() == BoardType.COMPANION)) {
                        throw new RuleViolationException(RuleErrorCode.LAST_COMMANDER_REQUIRED,
                                "Remove mainboard and companion cards before removing the last commander",
                                "quantity", List.of(existing.getOracleId()));
                    }
                    validateCommanders(remaining);
                }
                deck.removeCard(existing);
                accessories.removeOrphans(deck, existing);
            }
        } else {
            var details = integration.fetchCardDetails(request.oracleId());
            var candidate = new DeckCard(request.oracleId(), request.quantity(), request.boardType());
            // Validate a detached view excluding the replaced row, without mutating managed entities.
            Deck validationDeck = without(deck, existing);
            validator(deck).validateCardAddition(validationDeck, candidate, details);
            if (existing == null) {
                deck.addCard(candidate);
                if (DeckAccessoryService.generatesAccessories(candidate.getBoardType()))
                    accessories.addAccessories(deck, List.of(details));
            } else existing.setQuantity(request.quantity());
        }
        deck.invalidateAnalysis();
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    private List<CardDetailsResponse> validateCommanders(Deck deck) {
        var details = new java.util.ArrayList<CardDetailsResponse>();
        for (DeckCard card : deck.getCards()) {
            if (card.getBoardType() == BoardType.COMMANDER) {
                var commander = integration.fetchCardDetails(card.getOracleId());
                validator(deck).validateCardAddition(without(deck, card),
                        new DeckCard(card.getOracleId(), 1, BoardType.COMMANDER), commander);
                details.add(commander);
            }
        }
        return details;
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

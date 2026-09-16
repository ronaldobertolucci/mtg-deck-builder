package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class DeckAnalysisService {
    private final DeckRepository repository;
    private final CardIntegrationService integration;
    private final CardRuleOverrideService overrides;

    public DeckAnalysisService(DeckRepository repository, CardIntegrationService integration,
                               CardRuleOverrideService overrides) {
        this.repository = repository;
        this.integration = integration;
        this.overrides = overrides;
    }

    @Transactional
    public DeckResponse analyze(Long userId, UUID deckId) {
        Deck deck = repository.findOwnedForUpdate(deckId, userId).orElseThrow(DeckNotFoundException::new);
        List<String> violations = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        boolean commander = deck.getFormat() == Format.COMMANDER;
        long main = count(deck, BoardType.MAINBOARD);
        long side = count(deck, BoardType.SIDEBOARD);
        long leaders = count(deck, BoardType.COMMANDER);
        long companions = count(deck, BoardType.COMPANION);
        var leaderRows = deck.getCards().stream().filter(c -> c.getBoardType() == BoardType.COMMANDER).toList();
        if (commander) {
            if (main + leaders != 100) violations.add("Commander requires exactly 100 cards in mainboard + commanders.");
            if (side != 0) violations.add("Commander cannot have a sideboard.");
            if (leaderRows.isEmpty() || leaderRows.size() > 2 || leaderRows.stream().anyMatch(c -> c.getQuantity() != 1))
                violations.add("Commander requires one or two commanders, with one copy each.");
        } else {
            if (main < 60) violations.add("Constructed mainboard requires at least 60 cards.");
            if (side + companions > 15) violations.add("Sideboard + companion cannot exceed 15 cards.");
            if (leaders != 0) violations.add("Constructed decks cannot have commanders.");
        }
        var companionRows = deck.getCards().stream().filter(c -> c.getBoardType() == BoardType.COMPANION).toList();
        if (!companionRows.isEmpty()) {
            uncertainties.add("Companion-specific deckbuilding requirements are not implemented; compliance cannot be confirmed.");
            if (companionRows.size() != 1 || companions != 1) violations.add("Only one companion with quantity 1 is allowed.");
        }
        Map<UUID, Long> quantities = new LinkedHashMap<>();
        for (DeckCard card : deck.getCards()) {
            if (card.getQuantity() <= 0) violations.add("Card quantity must be positive: " + card.getOracleId());
            quantities.merge(card.getOracleId(), (long) card.getQuantity(), Long::sum);
        }
        Map<UUID, CardDetailsResponse> details = new HashMap<>();
        for (var entry : quantities.entrySet()) {
            UUID id = entry.getKey();
            CardDetailsResponse card;
            try {
                card = integration.refreshCardDetails(id);
            } catch (CardNotFoundException | CardManagerUnavailableException ex) {
                uncertainties.add("Current card metadata unavailable: " + id);
                continue;
            }
            if (card == null || !id.equals(card.oracleId())) {
                uncertainties.add("Missing or inconsistent card metadata: " + id);
                continue;
            }
            details.put(id, card);
            var legality = card.legalities().get(deck.getFormat().name().toLowerCase(Locale.ROOT));
            if (legality == null || legality == CardLegality.UNKNOWN) {
                uncertainties.add("Unknown format legality: " + id);
            } else {
                try {
                    int limit = CardLegalityRules.enforce(card, deck.getFormat(), overrides.getMaxCopies(card, commander ? 1 : 4));
                    if (entry.getValue() > limit) violations.add("Copy limit exceeded for " + id + ": " + entry.getValue() + " > " + limit);
                } catch (RuleViolationException ex) { violations.add(id + ": " + ex.getMessage()); }
            }
            if (companionRows.stream().anyMatch(c -> c.getOracleId().equals(id)) && !card.keywords().contains("Companion"))
                violations.add("Card does not have Companion: " + id);
        }
        if (commander && !leaderRows.isEmpty() && leaderRows.size() <= 2
                && leaderRows.stream().allMatch(c -> details.containsKey(c.getOracleId()))) {
            var team = leaderRows.stream().map(c -> details.get(c.getOracleId())).toList();
            try { CommanderPairRules.validate(team); }
            catch (RuleViolationException ex) { violations.add(ex.getMessage()); }
            Set<String> colors = new HashSet<>();
            team.forEach(c -> colors.addAll(c.colorIdentity()));
            details.forEach((id, card) -> {
                if (!colors.containsAll(card.colorIdentity())) violations.add("Card color identity is outside the commanders' identity: " + id);
            });
        }
        var status = !violations.isEmpty() ? DeckStatus.IRREGULAR
                : !uncertainties.isEmpty() ? DeckStatus.UNDEFINED : DeckStatus.REGULAR;
        List<String> messages = new ArrayList<>(violations);
        messages.addAll(uncertainties);
        deck.recordAnalysis(status, Instant.now(), messages);
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    private long count(Deck deck, BoardType board) {
        return deck.getCards().stream().filter(c -> c.getBoardType() == board).mapToLong(DeckCard::getQuantity).sum();
    }
}

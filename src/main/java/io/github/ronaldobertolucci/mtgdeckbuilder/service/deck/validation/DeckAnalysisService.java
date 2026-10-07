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
        List<AnalysisReason> violations = new ArrayList<>();
        List<AnalysisReason> uncertainties = new ArrayList<>();
        boolean commander = deck.getFormat() == Format.COMMANDER;
        long main = count(deck, BoardType.MAINBOARD);
        long side = count(deck, BoardType.SIDEBOARD);
        long leaders = count(deck, BoardType.COMMANDER);
        long companions = count(deck, BoardType.COMPANION);
        var leaderRows = deck.getCards().stream().filter(c -> c.getBoardType() == BoardType.COMMANDER).toList();
        if (commander) {
            if (main + leaders != 100) violations.add(reason(AnalysisReason.Severity.VIOLATION, "COMMANDER_DECK_SIZE_INVALID", "Commander requires exactly 100 cards in mainboard + commanders.", "actual", main + leaders, "required", 100));
            if (side != 0) violations.add(reason(AnalysisReason.Severity.VIOLATION, "BOARD_TYPE_NOT_SUPPORTED", "Commander cannot have a sideboard.", "boardType", "SIDEBOARD", "format", deck.getFormat().name()));
            if (leaderRows.isEmpty() || leaderRows.size() > 2 || leaderRows.stream().anyMatch(c -> c.getQuantity() != 1))
                violations.add(reason(AnalysisReason.Severity.VIOLATION, "INVALID_COMMANDER_SELECTION", "Commander requires one or two commanders, with one copy each.", "oracleIds", leaderRows.stream().map(c -> c.getOracleId().toString()).toList(), "quantities", leaderRows.stream().map(DeckCard::getQuantity).toList()));
        } else {
            if (main < 60) violations.add(reason(AnalysisReason.Severity.VIOLATION, "MAINBOARD_SIZE_BELOW_MINIMUM", "Constructed mainboard requires at least 60 cards.", "actual", main, "minimum", 60));
            if (side + companions > 15) violations.add(reason(AnalysisReason.Severity.VIOLATION, "SIDEBOARD_SIZE_LIMIT_EXCEEDED", "Sideboard + companion cannot exceed 15 cards.", "actual", side + companions, "maximum", 15));
            if (leaders != 0) violations.add(reason(AnalysisReason.Severity.VIOLATION, "BOARD_TYPE_NOT_SUPPORTED", "Constructed decks cannot have commanders.", "boardType", "COMMANDER", "format", deck.getFormat().name()));
        }
        var companionRows = deck.getCards().stream().filter(c -> c.getBoardType() == BoardType.COMPANION).toList();
        if (!companionRows.isEmpty()) {
            uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, "COMPANION_REQUIREMENTS_NOT_EVALUATED", "Companion-specific deckbuilding requirements are not implemented; compliance cannot be confirmed.", "oracleIds", companionRows.stream().map(c -> c.getOracleId().toString()).toList()));
            if (companionRows.size() != 1 || companions != 1) violations.add(reason(AnalysisReason.Severity.VIOLATION, "INVALID_COMPANION_SELECTION", "Only one companion with quantity 1 is allowed.", "oracleIds", companionRows.stream().map(c -> c.getOracleId().toString()).toList(), "quantities", companionRows.stream().map(DeckCard::getQuantity).toList()));
        }
        Map<UUID, Long> quantities = new LinkedHashMap<>();
        for (DeckCard card : deck.getCards()) {
            if (card.getBoardType() == BoardType.TOKENS) continue;
            if (card.getQuantity() <= 0) violations.add(reason(AnalysisReason.Severity.VIOLATION, "INVALID_CARD_QUANTITY", "Card quantity must be positive: " + card.getOracleId(), "oracleId", card.getOracleId().toString(), "actual", card.getQuantity()));
            quantities.merge(card.getOracleId(), (long) card.getQuantity(), Long::sum);
        }
        Map<UUID, CardDetailsResponse> details = new HashMap<>();
        for (var entry : quantities.entrySet()) {
            UUID id = entry.getKey();
            CardDetailsResponse card;
            try {
                card = integration.refreshCardDetails(id);
            } catch (CardNotFoundException | CardManagerUnavailableException ex) {
                uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, "CARD_METADATA_UNAVAILABLE", "Current card metadata unavailable: " + id, "oracleId", id.toString()));
                continue;
            }
            if (card == null || !id.equals(card.oracleId())) {
                uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, "CARD_METADATA_INCONSISTENT", "Missing or inconsistent card metadata: " + id, "oracleId", id.toString()));
                continue;
            }
            details.put(id, card);
            if (commander && card.colorIdentity() == null) {
                uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, "COLOR_IDENTITY_UNKNOWN", "Unknown color identity: " + id, "oracleId", id.toString()));
            }
            var legality = card.legalities().get(deck.getFormat().name().toLowerCase(Locale.ROOT));
            if (legality == null || legality == CardLegality.UNKNOWN) {
                uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, "CARD_LEGALITY_UNKNOWN", "Unknown format legality: " + id, "oracleId", id.toString(), "format", deck.getFormat().name()));
            } else {
                try {
                    int limit = CardLegalityRules.enforce(card, deck.getFormat(), overrides.getMaxCopies(card, commander ? 1 : 4));
                    if (entry.getValue() > limit) violations.add(reason(AnalysisReason.Severity.VIOLATION, "COPY_LIMIT_EXCEEDED", "Copy limit exceeded for " + id + ": " + entry.getValue() + " > " + limit, "oracleId", id.toString(), "actual", entry.getValue(), "maximum", limit));
                } catch (RuleViolationException ex) { violations.add(reason(AnalysisReason.Severity.VIOLATION, ex.getCode().name(), id + ": " + ex.getMessage(), "oracleId", id.toString(), "cardName", card.name(), "format", deck.getFormat().name(), "legality", legality.name())); }
            }
            if (companionRows.stream().anyMatch(c -> c.getOracleId().equals(id)) && !card.keywords().contains("Companion"))
                violations.add(reason(AnalysisReason.Severity.VIOLATION, "COMPANION_NOT_ELIGIBLE", "Card does not have Companion: " + id, "oracleId", id.toString()));
        }
        if (commander && !leaderRows.isEmpty() && leaderRows.size() <= 2
                && leaderRows.stream().allMatch(c -> details.containsKey(c.getOracleId()))) {
            var team = leaderRows.stream().map(c -> details.get(c.getOracleId())).toList();
            try { CommanderPairRules.validate(team); }
            catch (RuleViolationException ex) {
                if (ex.getCode() == io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.COMMANDER_DATA_INCOMPLETE)
                    uncertainties.add(reason(AnalysisReason.Severity.UNCERTAINTY, ex.getCode().name(), ex.getMessage(), "oracleIds", team.stream().map(c -> c.oracleId().toString()).toList()));
                else violations.add(reason(AnalysisReason.Severity.VIOLATION, ex.getCode().name(), ex.getMessage(), "oracleIds", team.stream().map(c -> c.oracleId().toString()).toList()));
            }
            // An incomplete commander identity cannot establish an out-of-identity violation.
            if (team.stream().allMatch(c -> c.colorIdentity() != null)) {
                Set<String> colors = new HashSet<>();
                team.forEach(c -> colors.addAll(c.colorIdentity()));
                details.forEach((id, card) -> {
                    if (card.colorIdentity() != null && !colors.containsAll(card.colorIdentity()))
                        violations.add(reason(AnalysisReason.Severity.VIOLATION, "COLOR_IDENTITY_INCOMPATIBLE", "Card color identity is outside the commanders' identity: " + id, "oracleId", id.toString(), "colorIdentity", card.colorIdentity(), "commanderColorIdentity", colors.stream().sorted().toList()));
                });
            }
        }
        var status = !violations.isEmpty() ? DeckStatus.IRREGULAR
                : !uncertainties.isEmpty() ? DeckStatus.UNDEFINED : DeckStatus.REGULAR;
        List<AnalysisReason> messages = new ArrayList<>(violations);
        messages.addAll(uncertainties);
        deck.recordStructuredAnalysis(status, Instant.now(), messages);
        return DeckResponse.from(repository.saveAndFlush(deck));
    }

    private AnalysisReason reason(AnalysisReason.Severity severity, String code, String message, Object... parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i += 2) values.put((String) parameters[i], parameters[i + 1]);
        return new AnalysisReason(code, severity, message, values);
    }

    private long count(Deck deck, BoardType board) {
        return deck.getCards().stream().filter(c -> c.getBoardType() == board).mapToLong(DeckCard::getQuantity).sum();
    }
}

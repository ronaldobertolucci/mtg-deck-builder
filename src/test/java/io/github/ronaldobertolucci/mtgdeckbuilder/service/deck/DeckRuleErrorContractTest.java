package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ImportDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.UpsertDeckCardRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures.legalities;
import static io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeckRuleErrorContractTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    private DeckService service;
    private final UUID deckId = UUID.randomUUID(), candidateId = UUID.randomUUID(), existingId = UUID.randomUUID();
    private final Deck deck = new Deck(42L, "Test", Format.MODERN);

    @BeforeEach void setup() {
        var overrides = new CardRuleOverrideService();
        service = new DeckService(repository, integration, List.of(new Constructed60Validator(overrides),
                new CommanderValidator(overrides, integration)), new DeckImportParserService());
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"STANDARD", "MODERN", "LEGACY", "PIONEER"})
    void combinedSideboardLimitIncludesCompanionInBothDirections(Format format) {
        deck.setFormat(format);
        deck.addCard(new DeckCard(existingId, 15, BoardType.SIDEBOARD));
        reject(BoardType.COMPANION, 1, true, SIDEBOARD_SIZE_LIMIT_EXCEEDED, "quantity", List.of(candidateId));
        deck.removeCard(deck.getCards().getFirst());
        deck.addCard(new DeckCard(existingId, 1, BoardType.COMPANION));
        reject(BoardType.SIDEBOARD, 15, false, SIDEBOARD_SIZE_LIMIT_EXCEEDED, "quantity", List.of(candidateId));
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"MODERN", "COMMANDER"})
    void companionQuantityIsStructured(Format format) {
        deck.setFormat(format);
        reject(BoardType.COMPANION, 2, true, INVALID_COMPANION_QUANTITY, "quantity", List.of(candidateId));
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"MODERN", "COMMANDER"})
    void companionEligibilityIsStructured(Format format) {
        deck.setFormat(format);
        reject(BoardType.COMPANION, 1, false, COMPANION_NOT_ELIGIBLE, "oracleId", List.of(candidateId));
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"MODERN", "COMMANDER"})
    void secondCompanionIdentifiesBothCards(Format format) {
        deck.setFormat(format);
        deck.addCard(new DeckCard(existingId, 1, BoardType.COMPANION));
        reject(BoardType.COMPANION, 1, true, COMPANION_LIMIT_EXCEEDED, "boardType", List.of(existingId, candidateId));
    }

    @Test void commanderQuantityReusesSelectionCode() {
        deck.setFormat(Format.COMMANDER);
        reject(BoardType.COMMANDER, 2, false, INVALID_COMMANDER_SELECTION, "quantity", List.of(candidateId));
    }

    @Test void thirdCommanderIdentifiesSelectedTeam() {
        deck.setFormat(Format.COMMANDER);
        var second = UUID.randomUUID();
        deck.addCard(new DeckCard(existingId, 1, BoardType.COMMANDER));
        deck.addCard(new DeckCard(second, 1, BoardType.COMMANDER));
        reject(BoardType.COMMANDER, 1, false, INVALID_COMMANDER_SELECTION,
                "commanderOracleIds", List.of(existingId, second, candidateId));
    }

    @ParameterizedTest
    @EnumSource(value = BoardType.class, names = {"MAINBOARD", "COMPANION"})
    void lastCommanderRemovalKeepsCardsAndAnalysis(BoardType remainingBoard) {
        deck.setFormat(Format.COMMANDER);
        deck.addCard(new DeckCard(candidateId, 1, BoardType.COMMANDER));
        deck.addCard(new DeckCard(existingId, 1, remainingBoard));
        reject(BoardType.COMMANDER, 0, false, LAST_COMMANDER_REQUIRED, "quantity", List.of(candidateId));
        verifyNoInteractions(integration);
    }

    @Test void constructedCommanderZoneIsStructured() {
        reject(BoardType.COMMANDER, 1, false, BOARD_TYPE_NOT_SUPPORTED, "boardType", List.of(candidateId));
    }

    @Test void commanderSideboardZoneIsStructured() {
        deck.setFormat(Format.COMMANDER);
        reject(BoardType.SIDEBOARD, 1, false, BOARD_TYPE_NOT_SUPPORTED, "boardType", List.of(candidateId));
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"MODERN", "COMMANDER"})
    void nonAccessoryInTokensIsStructured(Format format) {
        deck.setFormat(format);
        reject(BoardType.TOKENS, 1, false, CARD_NOT_ACCESSORY, "oracleId", List.of(candidateId));
    }

    @Test void companionQuantityUpdatePreservesExistingQuantity() {
        deck.addCard(new DeckCard(candidateId, 1, BoardType.COMPANION));
        reject(BoardType.COMPANION, 2, true, INVALID_COMPANION_QUANTITY, "quantity", List.of(candidateId));
    }

    @Test void importRejectsMultipleCompanionsWithStructuredIds() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(integration.fetchCardDetailsByName("First")).thenReturn(details(existingId, true));
        when(integration.fetchCardDetailsByName("Second")).thenReturn(details(candidateId, true));
        when(integration.fetchCardDetailsByName("Third")).thenReturn(details(deckId, true));
        var error = catchThrowableOfType(RuleViolationException.class, () -> service.importDeck(42L,
                new ImportDeckRequest("Imported", Format.MODERN, "Companion\n1 First\n1 Second\n1 Third")));
        assertProblem(error, COMPANION_LIMIT_EXCEEDED, "boardType", List.of(candidateId, deckId));
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test void importRejectsInvalidExistingCompanionQuantityWithStructuredId() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(integration.fetchCardDetailsByName("Companion")).thenReturn(details(existingId, true));
        when(integration.fetchCardDetailsByName("Land")).thenReturn(details(candidateId, false));
        var error = catchThrowableOfType(RuleViolationException.class, () -> service.importDeck(42L,
                new ImportDeckRequest("Imported", Format.MODERN, "1 Land\nCompanion\n2 Companion")));
        assertProblem(error, INVALID_COMPANION_QUANTITY, "quantity", List.of(existingId));
        verify(repository, times(1)).saveAndFlush(any());
    }

    private CardDetailsResponse details(UUID id, boolean companion) {
        return new CardDetailsResponse(id, "Example", "Basic Land", "", List.of(), legalities(),
                companion ? List.of("Companion") : List.of());
    }

    private void reject(BoardType board, int quantity, boolean companion, RuleErrorCode code,
                        String field, List<UUID> ids) {
        when(repository.findOwnedForUpdate(deckId, 42L)).thenReturn(Optional.of(deck));
        if (quantity > 0) when(integration.fetchCardDetails(candidateId)).thenReturn(details(candidateId, companion));
        deck.recordAnalysis(DeckStatus.IRREGULAR, Instant.now(), List.of("Previous analysis"));
        var originalCards = List.copyOf(deck.getCards());
        var originalQuantities = originalCards.stream().map(DeckCard::getQuantity).toList();
        var analyzedAt = deck.getAnalyzedAt();
        var error = catchThrowableOfType(RuleViolationException.class, () -> service.upsertCard(42L, deckId,
                new UpsertDeckCardRequest(candidateId, board, quantity)));
        assertProblem(error, code, field, ids);
        assertThat(deck.getCards()).containsExactlyElementsOf(originalCards);
        assertThat(deck.getCards()).extracting(DeckCard::getQuantity).containsExactlyElementsOf(originalQuantities);
        assertThat(deck.getStatus()).isEqualTo(DeckStatus.IRREGULAR);
        assertThat(deck.getAnalyzedAt()).isEqualTo(analyzedAt);
        assertThat(deck.getAnalysisMessages()).containsExactly("Previous analysis");
        verify(repository, never()).saveAndFlush(any());
    }

    private void assertProblem(RuleViolationException error, RuleErrorCode code, String field, List<UUID> ids) {
        assertThat(error).isNotNull();
        var request = new MockHttpServletRequest("PUT", "/decks/" + deckId + "/cards");
        var response = new GlobalExceptionHandler().handleRuleViolation(error, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getDetail()).isNotBlank();
        assertThat(response.getBody().getProperties()).containsEntry("code", code.name())
                .containsEntry("field", field).containsEntry("oracleIds", ids);
    }
}

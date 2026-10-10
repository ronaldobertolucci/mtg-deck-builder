package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardFaceResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.PrintDeckCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeckExportServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    DeckExportService service;
    UUID deckId = UUID.randomUUID();
    @BeforeEach void setup() {
        service = new DeckExportService(repository, integration,
                new ExportFormatterFactory(List.of(new ArenaExportFormatter(), new PlainTextExportFormatter())));
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void resolvesEachCardGroupsZonesAndUsesRequestedFormatter(ExportFormat format) {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        UUID plains = UUID.randomUUID(), forest = UUID.randomUUID();
        deck.addCard(new DeckCard(plains, 30, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(forest, 30, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(plains, 1, BoardType.SIDEBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(plains)).thenReturn(details(plains, "Plains"));
        when(integration.fetchCardDetails(forest)).thenReturn(details(forest, "Forest"));
        assertThat(service.exportDeck(deckId, format, 42L).content())
                .isEqualTo((format == ExportFormat.ARENA ? "Deck\n" : "")
                        + "30 Forest\n30 Plains\n\nSideboard\n1 Plains");
        verify(integration, times(2)).fetchCardDetails(plains);
        verify(integration).fetchCardDetails(forest);
        verifyNoMoreInteractions(integration);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"ARENA,jace", "PLAIN_TEXT,jace", "ARENA,esika", "PLAIN_TEXT,esika",
            "ARENA,invasion", "PLAIN_TEXT,invasion"})
    void exportsDoubleFacedCatalogCardsUsingOnlyFrontName(ExportFormat format, String fixture) throws java.io.IOException {
        String payload;
        try (var stream = getClass().getResourceAsStream("/cards/" + fixture + ".json")) {
            payload = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var card = tools.jackson.databind.json.JsonMapper.builder().build().readValue(payload, CardDetailsResponse.class);
        Deck deck = new Deck(42L, "Faces", Format.MODERN);
        deck.addCard(new DeckCard(card.oracleId(), 3, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(card.oracleId())).thenReturn(card);

        assertThat(service.exportDeck(deckId, format, 42L).content())
                .isEqualTo((format == ExportFormat.ARENA ? "Deck\n" : "") + "3 " + card.cardFaces().getFirst().name());
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void exportsFrontNamesAcrossAllIncludedZones(ExportFormat format) {
        Deck deck = new Deck(42L, "Faces", Format.COMMANDER);
        for (BoardType board : BoardType.values()) {
            UUID id = UUID.randomUUID();
            deck.addCard(new DeckCard(id, 2, board));
            if (board == BoardType.TOKENS) continue;
            when(integration.fetchCardDetails(id)).thenReturn(facedDetails(id, "Front // Back", "transform",
                    List.of(new CardFaceResponse("Front", null, null), new CardFaceResponse("Back", null, null))));
        }
        deck.addCard(DeckCard.generatedToken(UUID.randomUUID()));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));

        assertThat(service.exportDeck(deckId, format, 42L).content()).isEqualTo(format == ExportFormat.ARENA
                ? "Commander\n2 Front\n\nCompanion\n2 Front\n\nDeck\n2 Front\n\nSideboard\n2 Front"
                : "2 Front\n\nCommander\n2 Front\n\nCompanion\n2 Front\n\nSideboard\n2 Front");
        verify(integration, times(4)).fetchCardDetails(any());
        verifyNoMoreInteractions(integration);
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void tokenOnlyDeckExportsEmptyContentWithoutFetchingOrChangingAccessories(ExportFormat format) {
        Deck deck = new Deck(42L, "Accessories", Format.MODERN);
        var manual = new DeckCard(UUID.randomUUID(), 12, BoardType.TOKENS);
        var automatic = DeckCard.generatedToken(UUID.randomUUID());
        deck.addCard(manual);
        deck.addCard(automatic);
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));

        assertThat(service.exportDeck(deckId, format, 42L).content()).isEmpty();
        verifyNoInteractions(integration);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoMoreInteractions(repository);
        assertThat(deck.getCards()).containsExactly(manual, automatic);
        assertThat(manual.getQuantity()).isEqualTo(12);
        assertThat(manual.isAutoGenerated()).isFalse();
        assertThat(automatic.getQuantity()).isOne();
        assertThat(automatic.isAutoGenerated()).isTrue();
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void exportsFrontNameWhenDoubleFacedMetadataIsIncomplete(ExportFormat format) {
        UUID id = UUID.randomUUID();
        Deck deck = new Deck(42L, "Faces", Format.MODERN);
        deck.addCard(new DeckCard(id, 1, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        List<List<CardFaceResponse>> incompleteFaces = Arrays.asList(null, List.of(),
                Arrays.asList(null, new CardFaceResponse("Back", null, null)),
                List.of(new CardFaceResponse(null, null, null), new CardFaceResponse("Back", null, null)),
                List.of(new CardFaceResponse("  ", null, null), new CardFaceResponse("Back", null, null)));
        for (var faces : incompleteFaces) {
            when(integration.fetchCardDetails(id)).thenReturn(facedDetails(id, "Front //  Back", "modal_dfc", faces));
            assertThat(service.exportDeck(deckId, format, 42L).content())
                    .isEqualTo((format == ExportFormat.ARENA ? "Deck\n" : "") + "1 Front");
        }
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void exportsReversibleCardUsingFrontName(ExportFormat format) {
        UUID id = UUID.randomUUID();
        Deck deck = new Deck(42L, "Faces", Format.MODERN);
        deck.addCard(new DeckCard(id, 1, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(id)).thenReturn(facedDetails(id, "Front // Back", "reversible_card",
                List.of(new CardFaceResponse("Front", null, null), new CardFaceResponse("Back", null, null))));
        assertThat(service.exportDeck(deckId, format, 42L).content())
                .isEqualTo((format == ExportFormat.ARENA ? "Deck\n" : "") + "1 Front");
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void preservesCombinedNamesForSingleSidedMultifaceCards(ExportFormat format) {
        Deck deck = new Deck(42L, "Faces", Format.MODERN);
        for (String layout : List.of("split", "adventure", "flip")) {
            UUID id = UUID.randomUUID();
            deck.addCard(new DeckCard(id, 1, BoardType.MAINBOARD));
            when(integration.fetchCardDetails(id)).thenReturn(facedDetails(id, layout + " // Other", layout,
                    List.of(new CardFaceResponse(layout, null, null), new CardFaceResponse("Other", null, null))));
        }
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        assertThat(service.exportDeck(deckId, format, 42L).content())
                .isEqualTo((format == ExportFormat.ARENA ? "Deck\n" : "")
                        + "1 adventure // Other\n1 flip // Other\n1 split // Other");
    }

    @Test void missingOrUnownedDeckThrowsBeforeFetchingCards() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exportDeck(deckId, ExportFormat.ARENA, 42L))
                .isInstanceOf(DeckNotFoundException.class);
        verifyNoInteractions(integration);
    }

    @Test void emptyDeckExportsEmptyContent() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(new Deck(42L, "Empty", Format.MODERN)));
        assertThat(service.exportDeck(deckId, ExportFormat.ARENA, 42L).content()).isEmpty();
        verifyNoInteractions(integration);
    }

    @Test void integrationFailureDoesNotReturnPartialExport() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        UUID id = UUID.randomUUID();
        deck.addCard(new DeckCard(id, 1, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(id)).thenThrow(new CardManagerUnavailableException("Unavailable", null));
        assertThatThrownBy(() -> service.exportDeck(deckId, ExportFormat.ARENA, 42L))
                .isInstanceOf(CardManagerUnavailableException.class);
    }

    @Test void printCardsIncludesEveryStoredGroupWithoutFetchingDetails() {
        Deck deck = new Deck(42L, "Deck", Format.COMMANDER);
        var expected = new ArrayList<PrintDeckCardResponse>();
        for (BoardType board : BoardType.values()) {
            UUID id = UUID.randomUUID();
            deck.addCard(new DeckCard(id, 1, board));
            expected.add(new PrintDeckCardResponse(id, 1));
        }
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));

        assertThat(service.printCards(deckId, 42L).cards()).containsExactlyInAnyOrderElementsOf(expected);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(integration);
    }

    @Test void printCardsSumsRepeatedOracleAcrossGroups() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        UUID repeated = UUID.randomUUID(), other = UUID.randomUUID();
        deck.addCard(new DeckCard(repeated, 2, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(other, 4, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(repeated, 3, BoardType.SIDEBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));

        assertThat(service.printCards(deckId, 42L).cards()).containsExactlyInAnyOrder(
                new PrintDeckCardResponse(repeated, 5), new PrintDeckCardResponse(other, 4));
        verifyNoInteractions(integration);
    }

    @Test void printCardsOfEmptyDeckReturnsEmptyList() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(new Deck(42L, "Empty", Format.MODERN)));
        var response = service.printCards(deckId, 42L);
        assertThat(response.cards()).isEmpty();
        assertThat(response.compositionRevision())
                .isEqualTo("v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(service.printCards(deckId, 42L)).isEqualTo(response);
        verifyNoInteractions(integration);
    }

    @Test void replacingCardChangesRevisionEvenWithSameDistinctCountAndTotalQuantity() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        var card = new DeckCard(UUID.randomUUID(), 2, BoardType.MAINBOARD);
        deck.addCard(card);
        deck.addCard(new DeckCard(UUID.randomUUID(), 3, BoardType.SIDEBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var before = service.printCards(deckId, 42L);

        card.setOracleId(UUID.randomUUID());
        var after = service.printCards(deckId, 42L);

        assertThat(after.cards()).hasSameSizeAs(before.cards());
        assertThat(after.cards().stream().mapToInt(PrintDeckCardResponse::quantity).sum())
                .isEqualTo(before.cards().stream().mapToInt(PrintDeckCardResponse::quantity).sum());
        assertThat(after.compositionRevision()).isNotEqualTo(before.compositionRevision());
    }

    @ParameterizedTest @EnumSource(BoardType.class)
    void quantityChangeInAnyZoneChangesRevision(BoardType board) {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        var card = new DeckCard(UUID.randomUUID(), 1, board);
        deck.addCard(card);
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var before = service.printCards(deckId, 42L);

        card.setQuantity(2);
        var after = service.printCards(deckId, 42L);

        assertThat(after.cards()).containsExactly(new PrintDeckCardResponse(card.getOracleId(), 2));
        assertThat(after.compositionRevision()).isNotEqualTo(before.compositionRevision());
    }

    @Test void addingChangingAndRemovingAutomaticTokensChangesRevision() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        deck.addCard(new DeckCard(UUID.randomUUID(), 2, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var original = service.printCards(deckId, 42L);

        var token = DeckCard.generatedToken(UUID.randomUUID());
        deck.addCard(token);
        var added = service.printCards(deckId, 42L);
        assertThat(added.cards()).contains(new PrintDeckCardResponse(token.getOracleId(), 1));
        assertThat(added.compositionRevision()).isNotEqualTo(original.compositionRevision());

        token.setQuantity(3);
        var changed = service.printCards(deckId, 42L);
        assertThat(changed.cards()).contains(new PrintDeckCardResponse(token.getOracleId(), 3));
        assertThat(changed.compositionRevision()).isNotEqualTo(added.compositionRevision());

        deck.removeCard(token);
        var removed = service.printCards(deckId, 42L);
        assertThat(removed.compositionRevision()).isNotEqualTo(changed.compositionRevision());
        assertThat(removed).isEqualTo(original);
        verifyNoInteractions(integration);
    }

    @Test void movingCardsBetweenZonesPreservesRevision() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        var card = new DeckCard(UUID.randomUUID(), 1, BoardType.MAINBOARD);
        deck.addCard(card);
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var before = service.printCards(deckId, 42L);

        for (var board : BoardType.values()) {
            card.setBoardType(board);
            assertThat(service.printCards(deckId, 42L)).isEqualTo(before);
        }
    }

    @Test void normalizedIdsEntryOrderAndDuplicateDistributionPreserveRevision() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        var firstCard = new DeckCard(first, 5, BoardType.MAINBOARD);
        var secondCard = new DeckCard(second, 3, BoardType.SIDEBOARD);
        deck.addCard(secondCard);
        deck.addCard(firstCard);
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var before = service.printCards(deckId, 42L);
        assertThat(before.cards()).containsExactly(
                new PrintDeckCardResponse(first, 5), new PrintDeckCardResponse(second, 3));
        assertThat(before.compositionRevision())
                .isEqualTo("v1:5c46373baf957f1ea7d75b8a0f658c72acc43532526143b624da81953da0247e");

        deck.removeCard(firstCard);
        deck.removeCard(secondCard);
        deck.addCard(new DeckCard(first, 2, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"), 3, BoardType.SIDEBOARD));
        deck.addCard(new DeckCard(first, 2, BoardType.SIDEBOARD));
        deck.addCard(new DeckCard(first, 1, BoardType.COMMANDER));

        assertThat(service.printCards(deckId, 42L)).isEqualTo(before);
    }

    @Test void changingDeckAttributesPreservesRevision() {
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        deck.addCard(new DeckCard(UUID.randomUUID(), 2, BoardType.MAINBOARD));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var before = service.printCards(deckId, 42L);

        deck.setName("Renamed");
        deck.setFormat(Format.LEGACY);
        deck.recordAnalysis(DeckStatus.IRREGULAR, java.time.Instant.parse("2026-10-09T12:00:00Z"), List.of("Updated analysis"));

        assertThat(service.printCards(deckId, 42L)).isEqualTo(before);
    }

    @Test void printCardsOfMissingOrUnownedDeckThrows() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.printCards(deckId, 42L)).isInstanceOf(DeckNotFoundException.class);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(integration);
    }

    private CardDetailsResponse details(UUID id, String name) {
        return new CardDetailsResponse(id, name, "Basic Land", "", List.of(), Map.of(), List.of());
    }

    private CardDetailsResponse facedDetails(UUID id, String name, String layout, List<CardFaceResponse> faces) {
        return new CardDetailsResponse(id, name, null, null, List.of(), Map.of(), List.of(),
                null, null, null, List.of(), layout, List.of(), faces);
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
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
        assertThat(service.printCards(deckId, 42L).cards()).isEmpty();
        verifyNoInteractions(integration);
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
}

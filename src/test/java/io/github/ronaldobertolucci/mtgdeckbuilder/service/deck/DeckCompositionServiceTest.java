package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckCompositionResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeckCompositionServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    @InjectMocks DeckCompositionService service;
    private final UUID deckId = UUID.randomUUID();
    private final Deck deck = new Deck(42L, "Composition", Format.COMMANDER);

    @Test void groupsAllZonesPreservingCopiesAndAutomaticAccessories() {
        var main = add("Artifact Creature", "normal", 4, BoardType.MAINBOARD);
        var commander = add("Legendary Creature", "normal", 1, BoardType.COMMANDER);
        var side = add("Artifact Land", "normal", 2, BoardType.SIDEBOARD);
        var companion = add("Enchantment Creature", "normal", 1, BoardType.COMPANION);
        var token = add("Token Artifact Creature", "token", 3, BoardType.TOKENS);
        UUID automaticId = UUID.randomUUID();
        var automatic = DeckCard.generatedToken(automaticId);
        automatic.setQuantity(5);
        deck.addCard(automatic);
        metadata(automaticId, "Emblem", "emblem");

        var boards = composition().cardsByBoardAndType();

        assertThat(boards).containsOnlyKeys(BoardType.values());
        assertThat(boards.get(BoardType.MAINBOARD).get("Creature")).containsExactly(DeckCardResponse.from(main));
        assertThat(boards.get(BoardType.COMMANDER).get("Creature")).containsExactly(DeckCardResponse.from(commander));
        assertThat(boards.get(BoardType.SIDEBOARD).get("Land")).containsExactly(DeckCardResponse.from(side));
        assertThat(boards.get(BoardType.COMPANION).get("Creature")).containsExactly(DeckCardResponse.from(companion));
        assertThat(boards.get(BoardType.TOKENS).get("Token")).containsExactly(DeckCardResponse.from(token));
        assertThat(boards.get(BoardType.TOKENS).get("Emblem")).containsExactly(DeckCardResponse.from(automatic));
        var entries = boards.values().stream().flatMap(types -> types.values().stream()).flatMap(List::stream).toList();
        assertThat(entries).hasSize(deck.getCards().size());
        assertThat(entries.stream().mapToInt(DeckCardResponse::quantity).sum()).isEqualTo(16);
        assertThat(deck.getCards()).hasSize(6);
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"Artifact Land Creature,Land", "Enchantment Creature,Creature",
            "Artifact Planeswalker,Planeswalker", "Instant Sorcery,Instant", "Artifact Sorcery,Sorcery",
            "Artifact Enchantment,Artifact", "Enchantment,Enchantment", "Battle,Other"})
    void usesStatsPriorityForNormalCardsInEveryZone(String typeLine, String expected) {
        for (var board : BoardType.values()) add(typeLine, "normal", 2, board);
        var boards = composition().cardsByBoardAndType();
        for (var board : BoardType.values()) {
            assertThat(boards.get(board).get(expected)).hasSize(1);
            assertThat(boards.get(board).values().stream().flatMap(List::stream)).hasSize(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"token,Token Artifact Creature,Token", "double_faced_token,Token Land Creature,Token",
            "emblem,Emblem Planeswalker,Emblem", "normal,Dungeon,Dungeon", ",Dungeon,Dungeon"})
    void accessoryClassificationTakesPriorityOverOrdinaryTypes(String layout, String typeLine, String expected) {
        var card = add(typeLine, layout, 2, BoardType.TOKENS);
        var types = composition().cardsByBoardAndType().get(BoardType.TOKENS);
        assertThat(types.get(expected)).containsExactly(DeckCardResponse.from(card));
        assertThat(types.values().stream().flatMap(List::stream)).hasSize(1);
    }

    @Test void sameOracleInDifferentZonesKeepsSeparateEntriesAndFetchesMetadataOnce() {
        var main = add("Creature", "normal", 4, BoardType.MAINBOARD);
        var side = new DeckCard(main.getOracleId(), 2, BoardType.SIDEBOARD);
        deck.addCard(side);
        var boards = composition().cardsByBoardAndType();
        assertThat(boards.get(BoardType.MAINBOARD).get("Creature")).containsExactly(DeckCardResponse.from(main));
        assertThat(boards.get(BoardType.SIDEBOARD).get("Creature")).containsExactly(DeckCardResponse.from(side));
        verify(integration).fetchCardDetails(main.getOracleId());
        verifyNoMoreInteractions(integration);
    }

    @Test void emptyDeckReturnsAllZonesAndEmptyGroups() {
        var boards = composition().cardsByBoardAndType();
        assertThat(boards).containsOnlyKeys(BoardType.values());
        boards.values().forEach(types -> {
            assertThat(types).containsOnlyKeys("Creature", "Instant", "Sorcery", "Artifact", "Enchantment",
                    "Planeswalker", "Land", "Other", "Token", "Emblem", "Dungeon");
            types.values().forEach(cards -> assertThat(cards).isEmpty());
        });
        verifyNoInteractions(integration);
    }

    @Test void unknownMetadataFallsBackToOther() {
        var card = add(null, null, 1, BoardType.SIDEBOARD);
        assertThat(composition().cardsByBoardAndType().get(BoardType.SIDEBOARD).get("Other"))
                .containsExactly(DeckCardResponse.from(card));
    }

    @Test void missingOrUnownedDeckFailsBeforeFetchingMetadata() {
        assertThatThrownBy(() -> service.getDeckComposition(deckId, 42L)).isInstanceOf(DeckNotFoundException.class);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoInteractions(integration);
    }

    private DeckCompositionResponse composition() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        return service.getDeckComposition(deckId, 42L);
    }

    private DeckCard add(String typeLine, String layout, int quantity, BoardType board) {
        UUID id = UUID.randomUUID();
        var card = new DeckCard(id, quantity, board);
        deck.addCard(card);
        metadata(id, typeLine, layout);
        return card;
    }

    private void metadata(UUID id, String typeLine, String layout) {
        when(integration.fetchCardDetails(id)).thenReturn(new CardDetailsResponse(id, "Card", typeLine, "",
                List.of(), Map.of(), List.of(), 0.0, "", "common", List.of(), layout, List.of()));
    }
}

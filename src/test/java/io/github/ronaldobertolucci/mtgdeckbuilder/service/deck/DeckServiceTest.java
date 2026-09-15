package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DeckServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    DeckService service;
    UUID deckId = UUID.randomUUID(), oracleId = UUID.randomUUID();
    Deck deck = new Deck(42L, "Test", Format.MODERN);
    @BeforeEach void setup() {
        var overrides = new CardRuleOverrideService();
        service = new DeckService(repository, integration, List.of(new Constructed60Validator(overrides),
                new CommanderValidator(overrides, integration)));
    }
    void owned() { when(repository.findOwnedForUpdate(deckId, 42L)).thenReturn(Optional.of(deck)); }
    void saved() { when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0)); }
    void details() { when(integration.fetchCardDetails(oracleId)).thenReturn(
            new CardDetailsResponse(oracleId, "Example", "Creature", "", List.of("U"))); }
    UpsertDeckCardRequest request(int quantity) { return new UpsertDeckCardRequest(oracleId, BoardType.MAINBOARD, quantity); }

    @Test void createsConstructedOwnedByAuthenticatedUser() {
        saved();
        var response = service.create(42L, new CreateDeckRequest("Test", Format.PIONEER, null));
        assertThat(response.format()).isEqualTo(Format.PIONEER);
        verify(repository).saveAndFlush(argThat(value -> value.getUserId().equals(42L)));
        verifyNoInteractions(integration);
    }
    @Test void createsCommanderWithCardInCascade() {
        saved(); details();
        var response = service.create(42L, new CreateDeckRequest("Test", Format.COMMANDER, oracleId));
        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().getFirst().boardType()).isEqualTo(BoardType.COMMANDER);
        verify(integration).fetchCardDetails(oracleId);
    }
    @Test void upsertReplacesTotalAndKeepsSameRow() {
        owned(); saved(); details();
        DeckCard existing = new DeckCard(oracleId, 4, BoardType.MAINBOARD);
        deck.addCard(existing);
        service.upsertCard(42L, deckId, request(4));
        assertThat(existing.getQuantity()).isEqualTo(4);
        service.upsertCard(42L, deckId, request(2));
        assertThat(existing.getQuantity()).isEqualTo(2);
        assertThat(deck.getCards()).containsExactly(existing);
    }
    @Test void addsNewCard() {
        owned(); saved(); details();
        var response = service.upsertCard(42L, deckId, request(4));
        assertThat(response.cards().getFirst().quantity()).isEqualTo(4);
        assertThat(deck.getCards().getFirst().getDeck()).isSameAs(deck);
    }
    @Test void invalidReplacementLeavesOriginalQuantityAndDoesNotSave() {
        owned(); details();
        DeckCard existing = new DeckCard(oracleId, 3, BoardType.MAINBOARD);
        deck.addCard(existing);
        deck.addCard(new DeckCard(oracleId, 1, BoardType.SIDEBOARD));
        assertThatThrownBy(() -> service.upsertCard(42L, deckId, request(4))).isInstanceOf(RuleViolationException.class);
        assertThat(existing.getQuantity()).isEqualTo(3);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void zeroRemovesOnlyTargetBoardWithoutIntegrationAndIsIdempotent() {
        owned(); saved();
        deck.addCard(new DeckCard(oracleId, 3, BoardType.MAINBOARD));
        DeckCard sideboard = new DeckCard(oracleId, 1, BoardType.SIDEBOARD);
        deck.addCard(sideboard);
        service.upsertCard(42L, deckId, request(0));
        service.upsertCard(42L, deckId, request(0));
        assertThat(deck.getCards()).containsExactly(sideboard);
        verifyNoInteractions(integration);
    }
    @Test void missingOrOtherUsersDeckIsNotAccessible() {
        when(repository.findOwnedForUpdate(deckId, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsertCard(42L, deckId, request(1))).isInstanceOf(DeckNotFoundException.class);
        verifyNoInteractions(integration);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void commanderCannotBeRemovedWhileMainboardExists() {
        owned(); deck.setFormat(Format.COMMANDER);
        deck.addCard(new DeckCard(oracleId, 1, BoardType.COMMANDER));
        deck.addCard(new DeckCard(UUID.randomUUID(), 1, BoardType.MAINBOARD));
        assertThatThrownBy(() -> service.upsertCard(42L, deckId,
                new UpsertDeckCardRequest(oracleId, BoardType.COMMANDER, 0))).isInstanceOf(RuleViolationException.class);
        assertThat(deck.getCards()).hasSize(2);
    }
}

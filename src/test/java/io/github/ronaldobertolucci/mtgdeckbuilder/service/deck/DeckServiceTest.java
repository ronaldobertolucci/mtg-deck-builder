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
                new CommanderValidator(overrides, integration)), new DeckImportParserService());
    }
    void owned() { when(repository.findOwnedForUpdate(deckId, 42L)).thenReturn(Optional.of(deck)); }
    void saved() { when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0)); }
    void details() { when(integration.fetchCardDetails(oracleId)).thenReturn(
            new CardDetailsResponse(oracleId, "Example", "Legendary Creature", "", List.of("U"), io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures.legalities(), java.util.List.of())); }
    UpsertDeckCardRequest request(int quantity) { return new UpsertDeckCardRequest(oracleId, BoardType.MAINBOARD, quantity); }

    @Test void listsOwnerSummariesWithStablePagination() {
        var pageable = org.springframework.data.domain.PageRequest.of(1, 2,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt", "id"));
        when(repository.findByUserId(42L, pageable)).thenReturn(
                new org.springframework.data.domain.PageImpl<>(List.of(deck), pageable, 3));
        var result = service.list(42L, 1, 2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).extracting(DeckSummaryResponse::name).containsExactly("Test");
        verifyNoInteractions(integration);
    }

    @Test void getsOwnedDeckIncludingCardsAndAnalysis() {
        deck.addCard(new DeckCard(oracleId, 4, BoardType.MAINBOARD));
        deck.recordAnalysis(DeckStatus.IRREGULAR, java.time.Instant.now(), List.of("Too few cards"));
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        var result = service.get(42L, deckId);
        assertThat(result.cards()).hasSize(1);
        assertThat(result.analysisMessages()).containsExactly("Too few cards");
        assertThat(result.status()).isEqualTo(DeckStatus.IRREGULAR);
        verifyNoInteractions(integration);
    }

    @Test void renamePreservesFormatCardsAndAnalysis() {
        owned(); saved();
        deck.addCard(new DeckCard(oracleId, 4, BoardType.MAINBOARD));
        var at = java.time.Instant.now();
        deck.recordAnalysis(DeckStatus.IRREGULAR, at, List.of("Too few cards"));
        var result = service.rename(42L, deckId, new RenameDeckRequest("New name"));
        assertThat(result.name()).isEqualTo("New name");
        assertThat(result.format()).isEqualTo(Format.MODERN);
        assertThat(result.cards()).hasSize(1);
        assertThat(result.status()).isEqualTo(DeckStatus.IRREGULAR);
        assertThat(result.analyzedAt()).isEqualTo(at);
        assertThat(result.analysisMessages()).containsExactly("Too few cards");
        verifyNoInteractions(integration);
    }

    @Test void deletesOwnedDeckUnderLock() {
        owned();
        service.delete(42L, deckId);
        var order = inOrder(repository);
        order.verify(repository).findOwnedForUpdate(deckId, 42L);
        order.verify(repository).delete(deck);
        verifyNoInteractions(integration);
    }

    @Test void missingOrUnownedDeckCannotBeReadRenamedOrDeleted() {
        assertThatThrownBy(() -> service.get(42L, deckId)).isInstanceOf(DeckNotFoundException.class);
        assertThatThrownBy(() -> service.rename(42L, deckId, new RenameDeckRequest("New")))
                .isInstanceOf(DeckNotFoundException.class);
        assertThatThrownBy(() -> service.delete(42L, deckId)).isInstanceOf(DeckNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any(Deck.class));
        verifyNoInteractions(integration);
    }

    @Test void createsConstructedOwnedByAuthenticatedUser() {
        saved();
        var response = service.create(42L, new CreateDeckRequest("Test", Format.PIONEER, null));
        assertThat(response.format()).isEqualTo(Format.PIONEER);
        verify(repository).saveAndFlush(argThat(value -> value.getUserId().equals(42L)));
        verifyNoInteractions(integration);
    }
    @Test void createsCommanderWithCardInCascade() {
        saved(); details();
        var response = service.create(42L, new CreateDeckRequest("Test", Format.COMMANDER, List.of(oracleId)));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2, 4})
    void modificationInvalidatesAnalysis(int quantity) {
        owned(); saved();
        if (quantity != 0) details();
        deck.addCard(new DeckCard(oracleId, 1, BoardType.MAINBOARD));
        deck.recordAnalysis(DeckStatus.REGULAR, java.time.Instant.now(), List.of("Old result"));
        var result = service.upsertCard(42L, deckId, request(quantity));
        assertThat(result.status()).isEqualTo(DeckStatus.UNDEFINED);
        assertThat(result.analyzedAt()).isNull();
        assertThat(result.analysisMessages()).isEmpty();
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
    @Test void restrictedUpsertKeepsOneAndRejectsTwo() {
        owned(); saved();
        when(integration.fetchCardDetails(oracleId)).thenReturn(new CardDetailsResponse(oracleId, "Restricted", "Creature",
                "A deck can have any number of cards named Restricted.", List.of(),
                Map.of("modern", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.RESTRICTED), java.util.List.of()));
        service.upsertCard(42L, deckId, request(1));
        service.upsertCard(42L, deckId, request(1));
        assertThatThrownBy(() -> service.upsertCard(42L, deckId, request(2))).isInstanceOf(RuleViolationException.class);
        assertThat(deck.getCards().getFirst().getQuantity()).isEqualTo(1);
    }
    @Test void bannedCommanderCannotBeCreated() {
        when(integration.fetchCardDetails(oracleId)).thenReturn(new CardDetailsResponse(oracleId, "Banned", "Legendary Creature",
                "", List.of(), Map.of("commander", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.BANNED), java.util.List.of()));
        assertThatThrownBy(() -> service.create(42L, new CreateDeckRequest("Test", Format.COMMANDER, List.of(oracleId))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("BANNED");
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

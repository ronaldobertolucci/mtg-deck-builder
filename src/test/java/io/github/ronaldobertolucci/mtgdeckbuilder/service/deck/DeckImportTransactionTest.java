package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ImportDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import static io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures.legalities;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DeckService.class, DeckImportParserService.class, Constructed60Validator.class,
        CommanderValidator.class, CardRuleOverrideService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeckImportTransactionTest {
    @Autowired DeckService service;
    @Autowired DeckRepository decks;
    @Autowired DeckCardRepository cards;
    @MockitoBean CardIntegrationService integration;

    @AfterEach void cleanUp() { decks.deleteAll(); }

    private CardDetailsResponse details(String name, String type, List<String> keywords) {
        return new CardDetailsResponse(UUID.randomUUID(), name, type, "", List.of(), legalities(), keywords);
    }

    @Test void persistsMergedCardsAndCompanionWithGeneratedIds() {
        var bolt = details("Lightning Bolt", "Instant", List.of());
        var companion = details("Lurrus of the Dream-Den", "Legendary Creature", List.of("Companion"));
        when(integration.fetchCardDetailsByName(bolt.name())).thenReturn(bolt);
        when(integration.fetchCardDetailsByName(companion.name())).thenReturn(companion);
        var result = service.importDeck(42L, new ImportDeckRequest("Imported", Format.MODERN, """
                Deck
                2 Lightning Bolt (M11) 146
                1 Lightning Bolt
                Companion
                1 Lurrus of the Dream-Den (IKO) 226
                """));
        assertThat(result.id()).isNotNull();
        assertThat(decks.findById(result.id()).orElseThrow().getUserId()).isEqualTo(42L);
        assertThat(result.cards()).hasSize(2).allSatisfy(card -> assertThat(card.id()).isNotNull());
        assertThat(cards.findAll()).extracting(DeckCard::getQuantity).containsExactlyInAnyOrder(3, 1);
        assertThat(decks.count()).isEqualTo(1);
    }

    @Test void validationFailureRollsBackInitiallyFlushedDeckAndCards() {
        var bolt = details("Lightning Bolt", "Instant", List.of());
        when(integration.fetchCardDetailsByName(bolt.name())).thenAnswer(invocation -> {
            // The initial insert has really reached the database within the service transaction.
            assertThat(decks.count()).isEqualTo(1);
            return bolt;
        });
        assertThatThrownBy(() -> service.importDeck(42L,
                new ImportDeckRequest("Invalid", Format.MODERN, "4 Lightning Bolt\n1 Lightning Bolt")))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
        assertThat(decks.count()).isZero();
        assertThat(cards.count()).isZero();
    }

    @Test void lookupFailureRollsBackDeck() {
        when(integration.fetchCardDetailsByName("Missing")).thenThrow(new RuleViolationException("Card not found"));
        assertThatThrownBy(() -> service.importDeck(42L,
                new ImportDeckRequest("Missing", Format.MODERN, "1 Missing")))
                .isInstanceOf(RuleViolationException.class);
        assertThat(decks.count()).isZero();
        assertThat(cards.count()).isZero();
    }

    @Test void importsBackgroundBeforeItsCommander() {
        var background = details("Background", "Legendary Enchantment — Background", List.of());
        var commander = new CardDetailsResponse(UUID.randomUUID(), "Leader", "Legendary Creature",
                "Choose a Background", List.of(), legalities(), List.of());
        when(integration.fetchCardDetailsByName("Background")).thenReturn(background);
        when(integration.fetchCardDetailsByName("Leader")).thenReturn(commander);
        when(integration.fetchCardDetails(background.oracleId())).thenReturn(background);
        when(integration.fetchCardDetails(commander.oracleId())).thenReturn(commander);
        var result = service.importDeck(42L, new ImportDeckRequest("Pair", Format.COMMANDER,
                "Commander\n1 Background\n1 Leader"));
        assertThat(result.cards()).hasSize(2);
        assertThat(cards.count()).isEqualTo(2);
    }

    @Test void importsCommanderEvenWhenHeaderComesAfterMainboard() {
        var commander = details("Leader", "Legendary Creature", List.of());
        var land = details("Wastes", "Basic Land", List.of());
        when(integration.fetchCardDetailsByName("Leader")).thenReturn(commander);
        when(integration.fetchCardDetailsByName("Wastes")).thenReturn(land);
        when(integration.fetchCardDetails(commander.oracleId())).thenReturn(commander);
        when(integration.fetchCardDetails(land.oracleId())).thenReturn(land);
        var result = service.importDeck(42L,
                new ImportDeckRequest("Commander", Format.COMMANDER, "2 Wastes\nCommander\n1 Leader"));
        assertThat(result.cards()).hasSize(2);
        assertThat(cards.findAll()).extracting(DeckCard::getBoardType)
                .containsExactlyInAnyOrder(BoardType.COMMANDER, BoardType.MAINBOARD);
    }
}

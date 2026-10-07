package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.UpsertDeckCardRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DeckService.class, DeckImportParserService.class, CommanderValidator.class,
        Constructed60Validator.class, CardRuleOverrideService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeckRejectionTransactionTest {
    @Autowired DeckService service;
    @Autowired DeckRepository decks;
    @MockitoBean CardIntegrationService integration;

    @AfterEach void cleanUp() { decks.deleteAll(); }

    @ParameterizedTest
    @CsvSource({"MODERN_COPIES,true", "MODERN_COPIES,false", "COMMANDER_COPIES,true", "COMMANDER_COPIES,false",
            "COLOR,true", "COLOR,false", "SIZE,true", "SIZE,false"})
    void rejectionPreservesPersistedCardsQuantitiesAndAnalysis(String reason, boolean replacement) {
        UUID target = UUID.randomUUID(), commander = UUID.randomUUID();
        boolean constructed = reason.equals("MODERN_COPIES");
        Deck deck = new Deck(42L, "Saved", constructed ? Format.MODERN : Format.COMMANDER);
        if (!constructed) {
            deck.addCard(new DeckCard(commander, 1, BoardType.COMMANDER));
            when(integration.fetchCardDetails(commander)).thenReturn(details(commander, "Legendary Creature", List.of("U")));
        }
        // Non-copy failures use a basic land to isolate color and size rules.
        String type = reason.equals("COLOR") || reason.equals("SIZE") ? "Basic Land" : "Creature";
        int savedQuantity = constructed ? 3 : 1;
        if (replacement) deck.addCard(new DeckCard(target, savedQuantity, BoardType.MAINBOARD));
        if (reason.equals("SIZE"))
            deck.addCard(new DeckCard(UUID.randomUUID(), replacement ? 98 : 99, BoardType.MAINBOARD));
        deck.recordAnalysis(DeckStatus.IRREGULAR, Instant.parse("2026-10-06T12:00:00Z"), List.of("Análise salva"));
        UUID deckId = decks.saveAndFlush(deck).getId();
        var before = service.get(42L, deckId);
        when(integration.fetchCardDetails(target)).thenReturn(details(target, type,
                reason.equals("COLOR") ? List.of("R") : List.of("U")));
        int attemptedQuantity = constructed ? 5 : 2;
        RuleErrorCode code = switch (reason) {
            case "COLOR" -> RuleErrorCode.COLOR_IDENTITY_INCOMPATIBLE;
            case "SIZE" -> RuleErrorCode.COMMANDER_SIZE_LIMIT_EXCEEDED;
            default -> RuleErrorCode.COPY_LIMIT_EXCEEDED;
        };
        assertThatThrownBy(() -> service.upsertCard(42L, deckId,
                new UpsertDeckCardRequest(target, BoardType.MAINBOARD, attemptedQuantity)))
                .isInstanceOfSatisfying(RuleViolationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(code);
                    assertThat(ex.getOracleIds()).containsExactly(target);
                });
        // Read in a new transaction, after the rejected transaction has rolled back.
        assertThat(service.get(42L, deckId)).isEqualTo(before);
        assertThat(decks.count()).isEqualTo(1);
    }

    private CardDetailsResponse details(UUID id, String type, List<String> colors) {
        return new CardDetailsResponse(id, "<script>catalog name</script>", type, "", colors,
                CardTestFixtures.legalities(), List.of());
    }
}

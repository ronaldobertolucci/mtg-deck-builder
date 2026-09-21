package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManaSuggestionServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    @InjectMocks ManaSuggestionService service;
    private final UUID deckId = UUID.randomUUID();
    private final Deck deck = new Deck(42L, "Mana", Format.COMMANDER);

    @ParameterizedTest @NullSource @ValueSource(doubles = {0, 0.5, 1, 1.5})
    void earlyOrUnknownGeneratorsDoNotReduceDemand(Double cmc) {
        add("Creature", cmc, "{G}", List.of("G"), 2, BoardType.MAINBOARD);
        add("Instant", 1.0, "{U}", List.of(), 2, BoardType.MAINBOARD);
        assertThat(suggest(36)).containsEntry("GREEN", 18).containsEntry("BLUE", 18);
    }

    @ParameterizedTest @ValueSource(doubles = {2, 3, 7})
    void eligibleGeneratorsReduceDemandPerCopy(double cmc) {
        add("Creature", cmc, "{G}", List.of("G"), 2, BoardType.MAINBOARD);
        add("Instant", 1.0, "{G}{U}", List.of(), 2, BoardType.MAINBOARD);
        assertThat(suggest(36)).containsEntry("GREEN", 18).containsEntry("BLUE", 18);
    }

    @Test void doubleCountsHybridAndPhyrexianPipsAndIncludesCommander() {
        add("Instant", 4.0, "{2/W}{U/B}{G/P}{C}{X}", List.of(), 2, BoardType.MAINBOARD);
        add("Legendary Creature", 2.0, "{R}{R}", List.of(), 1, BoardType.COMMANDER);
        assertThat(suggest(10)).isEqualTo(Map.of("WHITE", 2, "BLUE", 2, "BLACK", 2, "RED", 2, "GREEN", 2));
    }

    @Test void ignoresLandsAndExcludedBoardsBeforeFetchingTheirMetadata() {
        add("Artifact Land", 3.0, "{G}", List.of("U"), 20, BoardType.MAINBOARD);
        add("Instant", 1.0, "{U}", null, 1, BoardType.MAINBOARD);
        UUID side = UUID.randomUUID(), companion = UUID.randomUUID();
        deck.addCard(new DeckCard(side, 15, BoardType.SIDEBOARD));
        deck.addCard(new DeckCard(companion, 1, BoardType.COMPANION));
        assertThat(suggest(36)).containsEntry("BLUE", 36).containsEntry("GREEN", 0);
        verify(integration, never()).fetchCardDetails(side);
        verify(integration, never()).fetchCardDetails(companion);
    }

    @Test void clampsEachColorBeforeSummingAndIgnoresColorlessGeneration() {
        add("Instant", 2.0, "{G}{U}{U}", List.of(), 1, BoardType.MAINBOARD);
        add("Artifact", 2.0, "{2}", List.of("G", "C"), 5, BoardType.MAINBOARD);
        assertThat(suggest(36)).containsEntry("GREEN", 0).containsEntry("BLUE", 36);
    }

    @Test void multicolorGeneratorsReduceEveryProducedColorOncePerCopy() {
        add("Instant", 3.0, "{W}{U}{U}{G}{G}{G}", List.of(), 1, BoardType.MAINBOARD);
        add("Artifact", 2.0, "{2}", List.of("W", "U", "G", "C"), 1, BoardType.MAINBOARD);
        assertThat(suggest(36)).containsEntry("WHITE", 0).containsEntry("BLUE", 12).containsEntry("GREEN", 24);
    }

    @Test void assignsRemainingLandsToLargestFractions() {
        add("Instant", 6.0, "{W}{U}{U}{B}{B}{B}", List.of(), 1, BoardType.MAINBOARD);
        assertThat(suggest(10)).isEqualTo(Map.of("WHITE", 2, "BLUE", 3, "BLACK", 5, "RED", 0, "GREEN", 0));
    }

    @Test void breaksEqualRemaindersInWubrgOrder() {
        add("Instant", 3.0, "{W}{U}{B}", List.of(), 1, BoardType.MAINBOARD);
        assertThat(suggest(2)).isEqualTo(Map.of("WHITE", 1, "BLUE", 1, "BLACK", 0, "RED", 0, "GREEN", 0));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 7, 35, 36, 37, 99, 100, Integer.MAX_VALUE})
    void preservesExactTotalEvenForRepeatingFractionsAndLargeTargets(int target) {
        add("Instant", 7.0, "{W}{U}{U}{B}{R}{G}{G}", List.of(), 3, BoardType.MAINBOARD);
        var result = suggest(target);
        assertThat(result).containsOnlyKeys("WHITE", "BLUE", "BLACK", "RED", "GREEN");
        assertThat(result.values()).allMatch(value -> value >= 0);
        assertThat(result.values().stream().mapToLong(Integer::longValue).sum()).isEqualTo(target);
    }

    @Test void emptyDeckReturnsEmptyMap() {
        assertThat(suggest(36)).isEmpty();
        verifyNoInteractions(integration);
    }

    @Test void colorlessAndLandOnlyCardsHaveNoDemand() {
        add("Artifact", 3.0, "{2}{C}{X}", List.of("C"), 4, BoardType.MAINBOARD);
        add("Basic Land", 0.0, null, List.of("G"), 20, BoardType.MAINBOARD);
        add(null, null, null, null, 1, BoardType.MAINBOARD);
        assertThat(suggest(36)).isEmpty();
    }

    @Test void fullyOffsetDemandReturnsEmptyMap() {
        add("Creature", 2.0, "{G}", List.of("G"), 4, BoardType.MAINBOARD);
        assertThat(suggest(36)).isEmpty();
    }

    @Test void missingOrUnownedDeckFailsBeforeFetchingCards() {
        assertThatThrownBy(() -> service.suggestManaBase(deckId, 42L, 36)).isInstanceOf(DeckNotFoundException.class);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoInteractions(integration);
    }

    @Test void rejectsNegativeTarget() {
        assertThatThrownBy(() -> service.suggestManaBase(deckId, 42L, -1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, integration);
    }

    private Map<String, Integer> suggest(int target) {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        return service.suggestManaBase(deckId, 42L, target).suggestedBasicLands();
    }

    private void add(String type, Double cmc, String cost, List<String> produced, int quantity, BoardType board) {
        UUID id = UUID.randomUUID();
        deck.addCard(new DeckCard(id, quantity, board));
        when(integration.fetchCardDetails(id)).thenReturn(new CardDetailsResponse(id, "Card", type, "",
                List.of(), Map.of(), List.of(), cmc, cost, "common", produced));
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
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
class DeckStatsServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    @InjectMocks DeckStatsService service;
    private final UUID deckId = UUID.randomUUID();
    private final Deck deck = new Deck(42L, "Stats", Format.COMMANDER);

    @Test void ignoresLandsForManaStatsAndWeightsByCopies() {
        add("Artifact Land Creature", 9.0, "{G/W}{B/P}{C}", "common", 10, BoardType.MAINBOARD);
        add("Creature", 4.0, "{1}{U}{U}{B}", "rare", 2, BoardType.MAINBOARD);
        add("Legendary Creature", 2.0, "{R}{G}", "mythic", 1, BoardType.COMMANDER);
        var stats = stats();
        assertThat(stats.totalCards()).isEqualTo(13);
        assertThat(stats.averageCmc()).isEqualTo(3.33);
        assertThat(stats.manaCurve()).containsEntry("4", 2).containsEntry("2", 1).containsEntry("7+", 0);
        assertThat(stats.manaCurve().values()).hasSize(8).containsOnly(0, 1, 2);
        assertThat(stats.colorPips()).isEqualTo(Map.of("WHITE", 0, "BLUE", 4, "BLACK", 2,
                "RED", 1, "GREEN", 1, "COLORLESS", 0));
        assertThat(stats.typeDistribution()).containsEntry("Land", 10).containsEntry("Creature", 3).containsEntry("Artifact", 0);
        assertThat(stats.rarityDistribution()).isEqualTo(Map.of("COMMON", 10, "UNCOMMON", 0, "RARE", 2, "MYTHIC", 1));
    }

    @Test void excludesSideboardAndCompanionBeforeFetchingMetadata() {
        UUID side = UUID.randomUUID(), companion = UUID.randomUUID();
        deck.addCard(new DeckCard(side, 15, BoardType.SIDEBOARD));
        deck.addCard(new DeckCard(companion, 1, BoardType.COMPANION));
        add("Creature", 3.0, "{W}", "rare", 1, BoardType.COMMANDER);
        assertThat(stats().totalCards()).isEqualTo(1);
        verify(integration, never()).fetchCardDetails(side);
        verify(integration, never()).fetchCardDetails(companion);
    }

    @Test void roundsCurveBucketsButUsesOriginalCmcForAverage() {
        add("Artifact", 6.5, "{C}", "uncommon", 2, BoardType.MAINBOARD);
        add("Creature", 12.0, "{10}{C}{C}", "mythic", 1, BoardType.MAINBOARD);
        add("Instant", 6.4, "", "common", 1, BoardType.MAINBOARD);
        var stats = stats();
        assertThat(stats.manaCurve()).containsEntry("7+", 3).containsEntry("6", 1);
        assertThat(stats.averageCmc()).isEqualTo(7.85);
        assertThat(stats.colorPips()).containsEntry("COLORLESS", 4);
    }

    @ParameterizedTest
    @CsvSource({"Artifact Creature,Creature", "Enchantment Creature,Creature", "Artifact Land,Land",
            "Planeswalker Creature,Creature", "Artifact Planeswalker,Planeswalker", "Instant Sorcery,Instant",
            "Artifact Sorcery,Sorcery", "Artifact Enchantment,Artifact", "Enchantment,Enchantment", "Battle,Other"})
    void usesStrictTypeHierarchyAndCountsEveryCopy(String type, String expected) {
        add(type, 1.0, "", "UnCoMmOn", 3, BoardType.MAINBOARD);
        var stats = stats();
        assertThat(stats.typeDistribution()).containsEntry(expected, 3);
        assertThat(stats.typeDistribution().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        assertThat(stats.rarityDistribution()).containsEntry("UNCOMMON", 3);
    }

    @Test void countsNormalHybridAndPhyrexianSymbolsTogether() {
        add("Instant", 4.0, "{1}{U}{U}{B}{W}{R}{G}{C}{X}{2/W}{U/B}{G/P}", "common", 2, BoardType.MAINBOARD);
        assertThat(stats().colorPips()).isEqualTo(Map.of("WHITE", 4, "BLUE", 6, "BLACK", 4,
                "RED", 2, "GREEN", 4, "COLORLESS", 2));
    }

    @ParameterizedTest
    @CsvSource({
            "{G/W},1,1,0,0,0,1,0",
            "{1}{U/R}{U/R},2,0,4,0,4,0,0",
            "{B/P},1,0,0,1,0,0,0",
            "{B/P},3,0,0,3,0,0,0",
            "{C},1,0,0,0,0,0,1",
            "{1}{U}{B},2,0,2,2,0,0,0",
            "{2/W},2,2,0,0,0,0,0",
            "{G/W/P},2,2,0,0,0,2,0",
            "{1}{2}{X},3,0,0,0,0,0,0"
    })
    void countsEachColorInManaBlockPerCopy(String cost, int quantity, int white, int blue,
                                          int black, int red, int green, int colorless) {
        add("Instant", 2.0, cost, "common", quantity, BoardType.MAINBOARD);
        assertThat(stats().colorPips()).isEqualTo(Map.of("WHITE", white, "BLUE", blue, "BLACK", black,
                "RED", red, "GREEN", green, "COLORLESS", colorless));
    }

    @Test void emptyDeckHasAllCategoriesAndZeroAverage() {
        var stats = stats();
        assertThat(stats.totalCards()).isZero();
        assertThat(stats.averageCmc()).isZero();
        assertThat(stats.manaCurve()).containsOnlyKeys("0", "1", "2", "3", "4", "5", "6", "7+");
        assertThat(stats.typeDistribution()).containsOnlyKeys("Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Land", "Other");
        assertThat(stats.colorPips()).containsOnlyKeys("WHITE", "BLUE", "BLACK", "RED", "GREEN", "COLORLESS");
        assertThat(stats.rarityDistribution()).containsOnlyKeys("COMMON", "UNCOMMON", "RARE", "MYTHIC");
        for (var counts : List.of(stats.manaCurve(), stats.typeDistribution(), stats.colorPips(), stats.rarityDistribution())) {
            assertThat(counts.values()).containsOnly(0);
        }
        verifyNoInteractions(integration);
    }

    @Test void landOnlyDeckHasZeroAverageAndManaStats() {
        add("Basic Land", 0.0, "", "common", 20, BoardType.MAINBOARD);
        var stats = stats();
        assertThat(stats.totalCards()).isEqualTo(20);
        assertThat(stats.averageCmc()).isZero();
        assertThat(stats.manaCurve().values()).containsOnly(0);
        assertThat(stats.colorPips().values()).containsOnly(0);
    }

    @Test void toleratesMissingOptionalMetadata() {
        add(null, null, null, null, 1, BoardType.MAINBOARD);
        var stats = stats();
        assertThat(stats.typeDistribution()).containsEntry("Other", 1);
        assertThat(stats.manaCurve()).containsEntry("0", 1);
        assertThat(stats.averageCmc()).isZero();
        assertThat(stats.colorPips().values()).containsOnly(0);
    }

    @Test void missingOrUnownedDeckFailsBeforeFetchingCards() {
        assertThatThrownBy(() -> service.getDeckStats(deckId, 42L)).isInstanceOf(DeckNotFoundException.class);
        verify(repository).findByIdAndUserId(deckId, 42L);
        verifyNoInteractions(integration);
    }

    private io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckStatsResponse stats() {
        when(repository.findByIdAndUserId(deckId, 42L)).thenReturn(Optional.of(deck));
        return service.getDeckStats(deckId, 42L);
    }

    private void add(String type, Double cmc, String cost, String rarity, int quantity, BoardType board) {
        UUID id = UUID.randomUUID();
        deck.addCard(new DeckCard(id, quantity, board));
        when(integration.fetchCardDetails(id)).thenReturn(new CardDetailsResponse(id, "Card", type, "",
                List.of(), Map.of(), List.of(), cmc, cost, rarity));
    }
}

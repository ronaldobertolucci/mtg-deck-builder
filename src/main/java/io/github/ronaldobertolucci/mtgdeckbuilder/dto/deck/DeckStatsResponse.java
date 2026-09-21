package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import java.util.Map;

public record DeckStatsResponse(
        int totalCards,
        double averageCmc,
        Map<String, Integer> manaCurve,
        Map<String, Integer> typeDistribution,
        Map<String, Integer> colorPips,
        Map<String, Integer> rarityDistribution
) {}

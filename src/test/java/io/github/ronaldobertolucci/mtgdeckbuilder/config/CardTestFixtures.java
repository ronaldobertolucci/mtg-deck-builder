package io.github.ronaldobertolucci.mtgdeckbuilder.config;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality;
import java.util.Map;
public final class CardTestFixtures {
    private CardTestFixtures() {}
    public static Map<String, CardLegality> legalities() {
        return Map.of("standard", CardLegality.LEGAL, "modern", CardLegality.LEGAL,
                "pioneer", CardLegality.LEGAL, "legacy", CardLegality.LEGAL, "commander", CardLegality.LEGAL);
    }
}

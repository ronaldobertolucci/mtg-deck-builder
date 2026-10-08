package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;

import java.util.List;

final class DeckCardTypeClassifier {
    static final List<String> TYPES = List.of("Creature", "Instant", "Sorcery", "Artifact",
            "Enchantment", "Planeswalker", "Land", "Other");
    static final List<String> ACCESSORY_TYPES = List.of("Token", "Emblem", "Dungeon");

    private DeckCardTypeClassifier() {}

    static String compositionType(CardDetailsResponse card) {
        if ("token".equals(card.layout()) || "double_faced_token".equals(card.layout())) return "Token";
        if ("emblem".equals(card.layout())) return "Emblem";
        if (card.typeLine() != null && card.typeLine().contains("Dungeon")) return "Dungeon";
        return mainType(card.typeLine());
    }

    static String mainType(String typeLine) {
        if (typeLine == null) return "Other";
        if (typeLine.contains("Land")) return "Land";
        else if (typeLine.contains("Creature")) return "Creature";
        else if (typeLine.contains("Planeswalker")) return "Planeswalker";
        else if (typeLine.contains("Instant")) return "Instant";
        else if (typeLine.contains("Sorcery")) return "Sorcery";
        else if (typeLine.contains("Artifact")) return "Artifact";
        else if (typeLine.contains("Enchantment")) return "Enchantment";
        else return "Other";
    }
}

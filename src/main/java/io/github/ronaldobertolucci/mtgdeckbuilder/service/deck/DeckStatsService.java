package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckStatsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DeckStatsService {
    private static final Pattern MANA_SYMBOL = Pattern.compile("\\{([^{}]+)\\}");
    private static final Map<String, String> PIP_COLORS = Map.of(
            "W", "WHITE", "U", "BLUE", "B", "BLACK", "R", "RED", "G", "GREEN", "C", "COLORLESS");
    private final DeckRepository repository;
    private final CardIntegrationService integration;

    public DeckStatsService(DeckRepository repository, CardIntegrationService integration) {
        this.repository = repository;
        this.integration = integration;
    }

    @Transactional(readOnly = true)
    public DeckStatsResponse getDeckStats(UUID deckId, Long userId) {
        var deck = repository.findByIdAndUserId(deckId, userId).orElseThrow(DeckNotFoundException::new);
        var curve = zeroCounts("0", "1", "2", "3", "4", "5", "6", "7+");
        var types = zeroCounts("Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Land", "Other");
        var pips = zeroCounts("WHITE", "BLUE", "BLACK", "RED", "GREEN", "COLORLESS");
        var rarities = zeroCounts("COMMON", "UNCOMMON", "RARE", "MYTHIC");
        int totalCards = 0;
        int nonLandCards = 0;
        double totalCmc = 0;

        for (var card : deck.getCards()) {
            if (card.getBoardType() != BoardType.MAINBOARD && card.getBoardType() != BoardType.COMMANDER) continue;
            var details = integration.fetchCardDetails(card.getOracleId());
            int quantity = card.getQuantity();
            totalCards += quantity;
            String type = mainType(details.typeLine());
            types.merge(type, quantity, Integer::sum);
            if (details.rarity() != null) {
                rarities.computeIfPresent(details.rarity().toUpperCase(Locale.ROOT), (key, count) -> count + quantity);
            }
            if (type.equals("Land")) continue;

            double cmc = details.cmc() == null ? 0 : details.cmc();
            long roundedCmc = Math.round(cmc);
            curve.merge(roundedCmc >= 7 ? "7+" : Long.toString(roundedCmc), quantity, Integer::sum);
            nonLandCards += quantity;
            totalCmc += cmc * quantity;
            countManaPips(details.manaCost(), quantity, pips);
        }
        double average = nonLandCards == 0 ? 0 : Math.round(totalCmc / nonLandCards * 100.0) / 100.0;
        return new DeckStatsResponse(totalCards, average, curve, types, pips, rarities);
    }

    private static void countManaPips(String manaCost, int quantity, Map<String, Integer> pips) {
        if (manaCost == null) return;
        var matcher = MANA_SYMBOL.matcher(manaCost);
        while (matcher.find()) {
            String symbol = matcher.group(1);
            // Hybrid symbols contribute once to each represented color per copy.
            PIP_COLORS.forEach((pip, color) -> {
                if (symbol.contains(pip)) pips.merge(color, quantity, Integer::sum);
            });
        }
    }

    private static Map<String, Integer> zeroCounts(String... keys) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : keys) counts.put(key, 0);
        return counts;
    }

    private static String mainType(String typeLine) {
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

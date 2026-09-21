package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ManaSuggestionResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ManaSuggestionService {
    private static final List<String> COLORS = List.of("WHITE", "BLUE", "BLACK", "RED", "GREEN");
    private static final List<String> SYMBOLS = List.of("W", "U", "B", "R", "G");
    private final DeckRepository repository;
    private final CardIntegrationService integration;

    public ManaSuggestionService(DeckRepository repository, CardIntegrationService integration) {
        this.repository = repository;
        this.integration = integration;
    }

    @Transactional(readOnly = true)
    public ManaSuggestionResponse suggestManaBase(UUID deckId, Long userId, int targetLands) {
        if (targetLands < 0) throw new IllegalArgumentException("targetLands must be non-negative");
        var deck = repository.findByIdAndUserId(deckId, userId).orElseThrow(DeckNotFoundException::new);
        Map<String, Integer> demand = zeroCounts();
        Map<String, Integer> generation = zeroCounts();
        for (var card : deck.getCards()) {
            if (card.getBoardType() != BoardType.MAINBOARD && card.getBoardType() != BoardType.COMMANDER) continue;
            var details = integration.fetchCardDetails(card.getOracleId());
            if (details.typeLine() != null && details.typeLine().contains("Land")) continue;
            DeckStatsService.countManaPips(details.manaCost(), card.getQuantity(), demand);
            // Unknown CMC and early generators cannot reduce the lands needed to cast them.
            if (details.cmc() != null && details.cmc() >= 2) {
                for (int i = 0; i < COLORS.size(); i++) {
                    if (details.producedMana().contains(SYMBOLS.get(i))) {
                        generation.merge(COLORS.get(i), card.getQuantity(), Integer::sum);
                    }
                }
            }
        }
        COLORS.forEach(color -> demand.compute(color, (key, count) -> Math.max(0, count - generation.get(color))));
        long total = demand.values().stream().mapToLong(Integer::longValue).sum();
        if (total == 0) return new ManaSuggestionResponse(Map.of());

        Map<String, Integer> result = zeroCounts();
        Map<String, Long> remainders = new HashMap<>();
        int assigned = 0;
        for (String color : COLORS) {
            long numerator = (long) demand.get(color) * targetLands;
            // Integer division equals floor(quota); modulo ranks the exact fractional remainders.
            int lands = (int) (numerator / total);
            result.put(color, lands);
            remainders.put(color, numerator % total);
            assigned += lands;
        }
        var ranked = new ArrayList<>(COLORS);
        // Stable sorting breaks ties in WUBRG order.
        ranked.sort(Comparator.comparingLong((String color) -> remainders.get(color)).reversed());
        for (int i = 0; i < targetLands - assigned; i++) {
            result.merge(ranked.get(i), 1, Integer::sum);
        }
        return new ManaSuggestionResponse(result);
    }

    private static Map<String, Integer> zeroCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        COLORS.forEach(color -> counts.put(color, 0));
        return counts;
    }
}

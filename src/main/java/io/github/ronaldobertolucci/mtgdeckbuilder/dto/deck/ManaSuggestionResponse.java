package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import java.util.Map;

public record ManaSuggestionResponse(Map<String, Integer> suggestedBasicLands) {
}

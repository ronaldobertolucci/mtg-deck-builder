package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import java.time.Instant;
import java.util.UUID;

public record DeckSummaryResponse(UUID id, String name, Format format, Instant createdAt,
                                  Instant updatedAt, DeckStatus status, Instant analyzedAt) {
    public static DeckSummaryResponse from(Deck deck) {
        return new DeckSummaryResponse(deck.getId(), deck.getName(), deck.getFormat(),
                deck.getCreatedAt(), deck.getUpdatedAt(), deck.getStatus(), deck.getAnalyzedAt());
    }
}

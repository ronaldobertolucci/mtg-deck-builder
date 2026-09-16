package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record DeckResponse(UUID id, String name, Format format, Instant createdAt, Instant updatedAt,
                           List<DeckCardResponse> cards, DeckStatus status, Instant analyzedAt, List<String> analysisMessages) {
    public static DeckResponse from(Deck deck) {
        return new DeckResponse(deck.getId(), deck.getName(), deck.getFormat(), deck.getCreatedAt(),
                deck.getUpdatedAt(), deck.getCards().stream().map(DeckCardResponse::from).toList(),
                deck.getStatus(), deck.getAnalyzedAt(), deck.getAnalysisMessages());
    }
}

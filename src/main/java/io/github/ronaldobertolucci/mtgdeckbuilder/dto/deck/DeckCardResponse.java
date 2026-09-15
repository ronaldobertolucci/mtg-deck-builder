package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import java.util.UUID;
public record DeckCardResponse(UUID id, UUID oracleId, BoardType boardType, int quantity) {
    public static DeckCardResponse from(DeckCard card) {
        return new DeckCardResponse(card.getId(), card.getOracleId(), card.getBoardType(), card.getQuantity());
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;

import java.util.List;
import java.util.UUID;

public record CardDetailsResponse(
        UUID oracleId,
        String name,
        String typeLine,
        String oracleText,
        List<String> colorIdentity
) {
    public CardDetailsResponse {
        colorIdentity = List.copyOf(colorIdentity);
    }
}

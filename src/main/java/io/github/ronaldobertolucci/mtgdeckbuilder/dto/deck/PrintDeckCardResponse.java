package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import java.util.UUID;

public record PrintDeckCardResponse(UUID oracleId, int quantity) {}

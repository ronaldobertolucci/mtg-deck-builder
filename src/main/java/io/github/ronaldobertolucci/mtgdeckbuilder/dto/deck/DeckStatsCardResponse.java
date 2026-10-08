package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;

import java.util.UUID;

public record DeckStatsCardResponse(UUID oracleId, BoardType boardType, int quantity) {}

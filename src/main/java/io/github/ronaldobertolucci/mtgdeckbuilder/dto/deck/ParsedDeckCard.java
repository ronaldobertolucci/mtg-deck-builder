package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;

public record ParsedDeckCard(String name, int quantity, BoardType boardType) {}

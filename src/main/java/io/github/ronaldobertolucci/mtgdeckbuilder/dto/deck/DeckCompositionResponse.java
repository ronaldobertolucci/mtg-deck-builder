package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;

import java.util.List;
import java.util.Map;

public record DeckCompositionResponse(Map<BoardType, Map<String, List<DeckCardResponse>>> cardsByBoardAndType) {}

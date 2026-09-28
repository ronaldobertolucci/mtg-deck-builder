package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import java.util.List;

public record PrintDeckResponse(List<PrintDeckCardResponse> cards) {}

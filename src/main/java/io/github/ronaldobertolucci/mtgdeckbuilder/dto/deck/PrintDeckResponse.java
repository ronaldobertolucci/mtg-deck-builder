package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import java.util.List;

public record PrintDeckResponse(String compositionRevision, List<PrintDeckCardResponse> cards) {}

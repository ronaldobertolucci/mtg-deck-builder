package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ExportableCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import java.util.List;
import java.util.Map;

public interface DeckExportFormatterStrategy {
    boolean supports(ExportFormat format);
    String format(String deckName, Map<BoardType, List<ExportableCard>> cardsByZone);
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ExportableCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class PlainTextExportFormatter implements DeckExportFormatterStrategy {
    @Override
    public boolean supports(ExportFormat format) { return format == ExportFormat.PLAIN_TEXT; }

    @Override
    public String format(String deckName, Map<BoardType, List<ExportableCard>> cardsByZone) {
        List<String> blocks = new ArrayList<>();
        for (BoardType zone : List.of(BoardType.MAINBOARD, BoardType.COMMANDER, BoardType.COMPANION, BoardType.SIDEBOARD)) {
            var cards = cardsByZone.getOrDefault(zone, List.of());
            if (cards.isEmpty()) continue;
            String header = switch (zone) {
                case COMMANDER -> "Commander\n";
                case COMPANION -> "Companion\n";
                case MAINBOARD -> "";
                case SIDEBOARD -> "Sideboard\n";
            };
            blocks.add(header + cards.stream().map(card -> card.quantity() + " " + card.name())
                    .collect(Collectors.joining("\n")));
        }
        return String.join("\n\n", blocks);
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ExportableCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ArenaExportFormatterTest {
    private final ArenaExportFormatter formatter = new ArenaExportFormatter();

    @Test void supportsOnlyItsFormat() {
        for (ExportFormat format : ExportFormat.values()) {
            assertThat(formatter.supports(format)).isEqualTo(format == ExportFormat.ARENA);
        }
    }

    @Test void formatsEveryZoneWithExactlyOneBlankLineBetweenBlocks() {
        var zones = Map.of(
                BoardType.MAINBOARD, List.of(new ExportableCard("Brainstorm", 4), new ExportableCard("Ponder", 4)),
                BoardType.COMMANDER, List.of(new ExportableCard("Atraxa, Praetors' Voice", 1)),
                BoardType.COMPANION, List.of(new ExportableCard("Keruga, the Macrosage", 1)),
                BoardType.SIDEBOARD, List.of(new ExportableCard("Force of Will", 2)));
        assertThat(formatter.format("Example", zones)).isEqualTo("Commander\n1 Atraxa, Praetors' Voice\n\nCompanion\n1 Keruga, the Macrosage\n\nDeck\n4 Brainstorm\n4 Ponder\n\nSideboard\n2 Force of Will");
    }

    @Test void omitsEmptyZonesAndDoesNotAddTrailingNewline() {
        assertThat(formatter.format("Example", Map.of(BoardType.MAINBOARD,
                List.of(new ExportableCard("Island", 20)), BoardType.SIDEBOARD, List.of())))
                .isEqualTo("Deck\n20 Island");
        assertThat(formatter.format("Empty", Map.of())).isEmpty();
        assertThat(formatter.format("Side only", Map.of(BoardType.SIDEBOARD,
                List.of(new ExportableCard("Island", 1)))))
                .isEqualTo("Sideboard\n1 Island");
    }
}

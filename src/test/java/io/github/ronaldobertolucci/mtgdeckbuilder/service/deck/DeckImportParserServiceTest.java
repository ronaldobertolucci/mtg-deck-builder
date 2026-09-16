package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ParsedDeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DeckImportParserServiceTest {
    private final DeckImportParserService parser = new DeckImportParserService();

    @Test void stripsArenaPrintingAndRecognizesCompanion() {
        assertThat(parser.parse("""
                Companion
                1 Lurrus of the Dream-Den (IKO) 226

                Deck
                4 Lightning Bolt (M11) 146
                Sideboard:
                2 Duress (M19) 94
                """)).containsExactly(
                new ParsedDeckCard("Lurrus of the Dream-Den", 1, BoardType.COMPANION),
                new ParsedDeckCard("Lightning Bolt", 4, BoardType.MAINBOARD),
                new ParsedDeckCard("Duress", 2, BoardType.SIDEBOARD));
    }

    @Test void plainTextDefaultsToMainboardAndPreservesNames() {
        assertThat(parser.parse("2 Fire // Ice\r\n\r\n1 Who // What // When // Where // Why"))
                .containsExactly(new ParsedDeckCard("Fire // Ice", 2, BoardType.MAINBOARD),
                        new ParsedDeckCard("Who // What // When // Where // Why", 1, BoardType.MAINBOARD));
    }

    @Test void headersIgnoreCaseWhitespaceAndOptionalColon() {
        assertThat(parser.parse("  cOmMaNdEr:  \n1 Example\nMaindeck:\n3 Island\nCOMPANION:\n1 Other"))
                .extracting(ParsedDeckCard::boardType)
                .containsExactly(BoardType.COMMANDER, BoardType.MAINBOARD, BoardType.COMPANION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Deck", "0 Island", "999999999999999 Island", "4 Island\nbad line"})
    void rejectsInvalidInputInsteadOfSilentlyDroppingCards(String text) {
        assertThatThrownBy(() -> parser.parse(text)).isInstanceOf(RuleViolationException.class);
    }
}

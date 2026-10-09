package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ParsedDeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DeckImportParserServiceTest {
    private final DeckImportParserService parser = new DeckImportParserService();

    @Test void reportsPhysicalLineIncludingHeadersAndBlankLines() {
        assertImportError("Deck\r\n\r\n4 Island\r\nbad line",
                RuleErrorCode.IMPORT_INVALID_LINE, 4);
    }

    @Test void reportsQuantityErrorsWithoutParsingTheMessage() {
        assertImportError("Deck\n0 Island", RuleErrorCode.IMPORT_INVALID_QUANTITY, 2);
        assertImportError("999999999999999 Island", RuleErrorCode.IMPORT_INVALID_QUANTITY, 1);
    }

    @Test void reportsWholeTextErrorsWithoutInventingALine() {
        assertImportError(null, RuleErrorCode.IMPORT_TEXT_REQUIRED, null);
        assertImportError("  ", RuleErrorCode.IMPORT_TEXT_REQUIRED, null);
        assertImportError("Deck\nSideboard", RuleErrorCode.IMPORT_NO_CARDS, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tokens", "Tokens:", "  tOkEnS:  ", "TOKENS\t"})
    void rejectsTokensHeaderWithPhysicalLineEvenWhenZoneIsEmpty(String header) {
        assertImportError("Deck\r\n\r\n1 Island\r\n" + header,
                RuleErrorCode.IMPORT_TOKENS_NOT_SUPPORTED, 4);
    }

    @Test void rejectsTokenOnlyImportBeforeParsingItsCards() {
        assertImportError("Tokens\n12 Soldier", RuleErrorCode.IMPORT_TOKENS_NOT_SUPPORTED, 1);
    }

    private void assertImportError(String text, RuleErrorCode code, Integer line) {
        assertThatThrownBy(() -> parser.parse(text)).isInstanceOfSatisfying(RuleViolationException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getField()).isEqualTo("rawText");
            assertThat(ex.getLine()).isEqualTo(line);
        });
    }

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

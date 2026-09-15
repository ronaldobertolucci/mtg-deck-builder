package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class Constructed60ValidatorTest {
    @Spy
    private CardRuleOverrideService overrides;
    @InjectMocks
    private Constructed60Validator validator;

    private final UUID oracleId = UUID.randomUUID();
    private final Deck deck = new Deck(1L, "Test", Format.MODERN);

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"STANDARD", "MODERN", "LEGACY", "PIONEER"})
    void supportsConstructedFormatsAndAllowsIncompleteDeck(Format format) {
        deck.setFormat(format);
        var details = details("Creature", "");
        assertThat(validator.supports(format)).isTrue();
        assertThatCode(() -> validator.validateCardAddition(deck, addition(4, BoardType.MAINBOARD), details))
                .doesNotThrowAnyException();
        verify(overrides).getMaxCopies(details);
        assertThat(deck.getCards()).isEmpty();
    }

    @Test
    void allowsFourCopiesAcrossBothBoards() {
        deck.addCard(addition(3, BoardType.MAINBOARD));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1, BoardType.SIDEBOARD), details("Creature", "")))
                .doesNotThrowAnyException();
        assertThat(deck.getCards().getFirst().getQuantity()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = BoardType.class, names = {"MAINBOARD", "SIDEBOARD"})
    void rejectsFifthCopyAcrossBoards(BoardType destination) {
        deck.addCard(addition(3, BoardType.MAINBOARD));
        deck.addCard(addition(1, BoardType.SIDEBOARD));
        assertViolation(addition(1, destination), details("Creature", ""), "Copy limit");
    }

    @Test
    void addsRequestedQuantityNotJustOne() {
        deck.addCard(addition(2, BoardType.MAINBOARD));
        assertViolation(addition(3, BoardType.MAINBOARD), details("Creature", ""), "Copy limit");
    }

    @Test
    void unrelatedOracleIdsDoNotCountTowardCopyLimit() {
        deck.addCard(new DeckCard(UUID.randomUUID(), 4, BoardType.MAINBOARD));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(4, BoardType.MAINBOARD), details("Creature", "")))
                .doesNotThrowAnyException();
    }

    @Test
    void explicitSevenCopyOverrideIsRespectedAcrossBoards() {
        deck.addCard(addition(6, BoardType.MAINBOARD));
        var details = details("Creature", "A deck can have up to seven cards named Example.");
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1, BoardType.SIDEBOARD), details))
                .doesNotThrowAnyException();
        assertViolation(addition(2, BoardType.SIDEBOARD), details, "Copy limit");
    }

    @Test
    void basicLandsAndUnlimitedTextCanExceedFourAndSixty() {
        for (var details : List.of(details("Basic Land — Island", ""),
                details("Creature", "A deck can have any number of cards named Example."))) {
            assertThatCode(() -> validator.validateCardAddition(deck, addition(70, BoardType.MAINBOARD), details))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void sideboardCountsQuantitiesAndAllOracleIdsEvenWithUnlimitedOverride() {
        deck.addCard(new DeckCard(UUID.randomUUID(), 14, BoardType.SIDEBOARD));
        var details = details("Basic Land — Island", "");
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1, BoardType.SIDEBOARD), details))
                .doesNotThrowAnyException();
        assertViolation(addition(2, BoardType.SIDEBOARD), details, "Sideboard");
        assertThatCode(() -> validator.validateCardAddition(deck, addition(2, BoardType.MAINBOARD), details))
                .doesNotThrowAnyException();
    }

    @Test
    void countsDoNotOverflowInteger() {
        deck.addCard(new DeckCard(UUID.randomUUID(), Integer.MAX_VALUE, BoardType.SIDEBOARD));
        assertViolation(addition(Integer.MAX_VALUE, BoardType.SIDEBOARD), details("Basic Land", ""), "Sideboard");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantity(int quantity) {
        assertViolation(addition(quantity, BoardType.MAINBOARD), details("Creature", ""), "positive");
    }

    @Test
    void rejectsWrongMetadataAndAlreadyAddedCard() {
        var card = addition(1, BoardType.MAINBOARD);
        var wrongDetails = new CardDetailsResponse(UUID.randomUUID(), "Other", "Creature", "", List.of());
        assertViolation(card, wrongDetails, "match");
        deck.addCard(card);
        assertViolation(card, details("Creature", ""), "before adding");
    }

    @Test
    void rejectsCommanderFormatAndZone() {
        assertThat(validator.supports(Format.COMMANDER)).isFalse();
        assertViolation(addition(1, BoardType.COMMANDER), details("Creature", ""), "cannot have a commander");
        deck.setFormat(Format.COMMANDER);
        assertViolation(addition(1, BoardType.MAINBOARD), details("Creature", ""), "Unsupported");
    }

    private DeckCard addition(int quantity, BoardType board) {
        return new DeckCard(oracleId, quantity, board);
    }

    private CardDetailsResponse details(String type, String text) {
        return new CardDetailsResponse(oracleId, "Example", type, text, List.of("U"));
    }

    private void assertViolation(DeckCard card, CardDetailsResponse details, String message) {
        assertThatThrownBy(() -> validator.validateCardAddition(deck, card, details))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining(message);
    }
}

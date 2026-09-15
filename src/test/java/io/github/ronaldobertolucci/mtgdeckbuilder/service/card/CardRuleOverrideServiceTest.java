package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardRuleOverrideServiceTest {
    private final CardRuleOverrideService service = new CardRuleOverrideService();

    @ParameterizedTest
    @ValueSource(strings = {
            "A deck can have any number of cards named Relentless Rats.",
            "Other ability.\nA DECK can have ANY NUMBER of cards named Persistent Petitioners.",
            "A deck can have any\nnumber of cards named Shadowborn Apostle."
    })
    void recognizesUnlimitedCopies(String text) {
        assertThat(service.getMaxCopies(card("Creature", text))).isEqualTo(Integer.MAX_VALUE);
        assertThat(service.getMaxCopies(card("Creature", text), 1)).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"one,1", "four,4", "seven,7", "nine,9", "THIRTEEN,13", "twenty,20",
            "twenty-one,21", "thirty two,32", "ninety-nine,99", "7,7"})
    void convertsExplicitLimits(String word, int expected) {
        var card = card("Creature", "A deck can have up to " + word + " cards named Example.");
        assertThat(service.getMaxCopies(card)).isEqualTo(expected);
        assertThat(service.getMaxCopies(card, 1)).isEqualTo(expected);
    }

    @Test
    void handlesWhitespaceCaseAndSurroundingText() {
        assertThat(service.getMaxCopies(card("Creature",
                "Flying.\nA DECK can have UP TO\nseven  cards named Example. Another ability."))).isEqualTo(7);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Flying", "You may draw up to seven cards.", "Any number of cards named Example.",
            "A deck can have up to many cards named Example.",
            "A deck can have up to zero cards named Example.",
            "A deck can have up to -1 cards named Example.",
            "A deck can have up to 999999999999 cards named Example.",
            "A deck can have up to twenty thirteen cards named Example.",
            "A deck can have up to seven creatures named Example.",
            "Um deck pode ter qualquer número de cards com o nome Exemplo."
    })
    void unrelatedOrMalformedTextKeepsFormatDefault(String text) {
        assertThat(service.getMaxCopies(card("Creature", text))).isEqualTo(4);
        assertThat(service.getMaxCopies(card("Creature", text), 1)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic Land — Island", "Basic Snow Land — Forest", "basic Land — Wastes"})
    void basicTypeOverridesCopyLimit(String type) {
        assertThat(service.getMaxCopies(card(type, null), 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(service.getMaxCopies(card(type, "A deck can have up to seven cards named Example.")))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Nonbasic Land", "Creature", "Land — Island"})
    void nonBasicTypesDoNotGrantUnlimitedCopies(String type) {
        assertThat(service.getMaxCopies(card(type, ""))).isEqualTo(4);
    }

    private CardDetailsResponse card(String type, String text) {
        return new CardDetailsResponse(UUID.randomUUID(), "Example", type, text, List.of());
    }
}

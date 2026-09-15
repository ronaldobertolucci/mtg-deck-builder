package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardLegalityValidationTest {
    @Mock CardIntegrationService integration;
    UUID oracleId = UUID.randomUUID();

    FormatValidatorStrategy strategy(Format format) {
        var overrides = new CardRuleOverrideService();
        return format == Format.COMMANDER ? new CommanderValidator(overrides, integration) : new Constructed60Validator(overrides);
    }
    Deck deck(Format format) {
        var deck = new Deck(1L, "Test", format);
        if (format == Format.COMMANDER) {
            UUID commanderId = UUID.randomUUID();
            deck.addCard(new DeckCard(commanderId, 1, BoardType.COMMANDER));
            lenient().when(integration.fetchCardDetails(commanderId)).thenReturn(new CardDetailsResponse(
                    commanderId, "Commander", "Legendary Creature", "", List.of(), Map.of("commander", CardLegality.LEGAL)));
        }
        return deck;
    }
    CardDetailsResponse details(Format format, CardLegality legality, String type, String text) {
        return new CardDetailsResponse(oracleId, "Example", type, text, List.of(),
                Map.of(format.name().toLowerCase(Locale.ROOT), legality));
    }
    void validate(Deck deck, CardDetailsResponse details, int quantity, BoardType board) {
        strategy(deck.getFormat()).validateCardAddition(deck, new DeckCard(oracleId, quantity, board), details);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void bannedAndNotLegalCardsAreRejectedEvenWithCopyOverrides(Format format) {
        var deck = deck(format);
        for (var legality : List.of(CardLegality.BANNED, CardLegality.NOT_LEGAL)) {
            assertThatThrownBy(() -> validate(deck, details(format, legality, "Basic Land",
                    "A deck can have any number of cards named Example."), 1, BoardType.MAINBOARD))
                    .isInstanceOf(RuleViolationException.class).hasMessageContaining(legality.name());
        }
    }

    @ParameterizedTest @EnumSource(Format.class)
    void restrictedAllowsOneButNeverTwoEvenWithOverrides(Format format) {
        var deck = deck(format);
        for (var card : List.of(details(format, CardLegality.RESTRICTED, "Creature", ""),
                details(format, CardLegality.RESTRICTED, "Basic Land", ""),
                details(format, CardLegality.RESTRICTED, "Creature", "A deck can have any number of cards named Example."),
                details(format, CardLegality.RESTRICTED, "Creature", "A deck can have up to seven cards named Example."))) {
            assertThatCode(() -> validate(deck, card, 1, BoardType.MAINBOARD)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validate(deck, card, 2, BoardType.MAINBOARD))
                    .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
        }
    }

    @ParameterizedTest @EnumSource(value=Format.class, names={"STANDARD","MODERN","PIONEER","LEGACY"})
    void restrictedCountsBothBoards(Format format) {
        var deck = deck(format);
        deck.addCard(new DeckCard(oracleId, 1, BoardType.SIDEBOARD));
        assertThatThrownBy(() -> validate(deck, details(format, CardLegality.RESTRICTED, "Creature", ""), 1, BoardType.MAINBOARD))
                .isInstanceOf(RuleViolationException.class);
        var reverse = deck(format);
        reverse.addCard(new DeckCard(oracleId, 1, BoardType.MAINBOARD));
        assertThatThrownBy(() -> validate(reverse, details(format, CardLegality.RESTRICTED, "Creature", ""), 1, BoardType.SIDEBOARD))
                .isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void legalCardsKeepFormatAndTextRules(Format format) {
        var deck = deck(format);
        assertThatCode(() -> validate(deck, details(format, CardLegality.LEGAL, "Basic Land", ""), 20, BoardType.MAINBOARD))
                .doesNotThrowAnyException();
        int limit = format == Format.COMMANDER ? 1 : 4;
        assertThatCode(() -> validate(deck, details(format, CardLegality.LEGAL, "Creature", ""), limit, BoardType.MAINBOARD))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(deck, details(format, CardLegality.LEGAL, "Creature", ""), limit + 1, BoardType.MAINBOARD))
                .isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void missingOrUnknownLegalityIsRejected(Format format) {
        var deck = deck(format);
        for (var legalities : Arrays.asList(null, Map.<String, CardLegality>of(),
                Map.of("vintage", CardLegality.LEGAL), Map.of(format.name(), CardLegality.UNKNOWN))) {
            var card = new CardDetailsResponse(oracleId, "Example", "Creature", "", List.of(), legalities);
            assertThatThrownBy(() -> validate(deck, card, 1, BoardType.MAINBOARD))
                    .isInstanceOf(RuleViolationException.class).hasMessageContaining("UNKNOWN");
        }
    }

    @Test void legalityIsSelectedForTheDeckFormatAndKeysAreCaseInsensitive() {
        var card = new CardDetailsResponse(oracleId, "Example", "Creature", "", List.of(),
                Map.of("MODERN", CardLegality.BANNED, "LEGACY", CardLegality.LEGAL));
        assertThatThrownBy(() -> validate(deck(Format.MODERN), card, 1, BoardType.MAINBOARD)).isInstanceOf(RuleViolationException.class);
        assertThatCode(() -> validate(deck(Format.LEGACY), card, 4, BoardType.MAINBOARD)).doesNotThrowAnyException();
    }

    @Test void bannedCommanderIsRejected() {
        var empty = new Deck(1L, "Test", Format.COMMANDER);
        assertThatThrownBy(() -> validate(empty, details(Format.COMMANDER, CardLegality.BANNED, "Legendary Creature", ""),
                1, BoardType.COMMANDER)).isInstanceOf(RuleViolationException.class).hasMessageContaining("BANNED");
    }

    @Test void legalityDeserializerAcceptsBothCasesAndRejectsUnknown() {
        assertThat(CardLegality.fromValue("restricted")).isEqualTo(CardLegality.RESTRICTED);
        assertThat(CardLegality.fromValue("NOT_LEGAL")).isEqualTo(CardLegality.NOT_LEGAL);
        assertThat(CardLegality.fromValue("unexpected")).isEqualTo(CardLegality.UNKNOWN);
    }
}

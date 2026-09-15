package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardRuleOverrideService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommanderValidatorTest {
    @Spy
    private CardRuleOverrideService overrides;
    @Mock
    private CardIntegrationService integration;
    @InjectMocks
    private CommanderValidator validator;

    private final UUID oracleId = UUID.randomUUID();
    private final UUID commanderId = UUID.randomUUID();
    private final Deck deck = new Deck(1L, "Commander", Format.COMMANDER);

    @Test
    void allowsCommanderAsFirstCardWithoutHttpLookupOrMutation() {
        assertThat(validator.supports(Format.COMMANDER)).isTrue();
        assertThatCode(() -> validator.validateCardAddition(deck,
                new DeckCard(commanderId, 1, BoardType.COMMANDER), commanderDetails(List.of("U", "B"))))
                .doesNotThrowAnyException();
        verifyNoInteractions(integration);
        assertThat(deck.getCards()).isEmpty();
    }

    @Test
    void allowsOneCopyWithinCommanderColorsAndUsesSingletonDefault() {
        addCommander();
        mockCommanderColors(List.of("U", "B"));
        var details = details("Creature", "", List.of("U"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details)).doesNotThrowAnyException();
        verify(overrides).getMaxCopies(details, 1);
        verify(integration).fetchCardDetails(commanderId);
        assertThat(deck.getCards()).hasSize(1);
    }

    @Test
    void rejectsDuplicateOracleEvenWithDifferentDisplayName() {
        addCommander();
        deck.addCard(addition(1));
        assertViolation(addition(1), details("Creature", "", List.of()), "Copy limit");
        verifyNoInteractions(integration);
    }

    @Test
    void rejectsBatchOfTwoOrdinaryCards() {
        addCommander();
        assertViolation(addition(2), details("Creature", "", List.of()), "Copy limit");
    }

    @Test
    void commanderItselfCountsTowardCopyLimit() {
        addCommander();
        assertViolation(new DeckCard(commanderId, 1, BoardType.MAINBOARD), commanderDetails(List.of()), "Copy limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"four", "seven", "nine"})
    void explicitCopyOverrideReplacesSingletonLimit(String word) {
        addCommander();
        mockCommanderColors(List.of("U"));
        int limit = switch (word) { case "four" -> 4; case "seven" -> 7; default -> 9; };
        deck.addCard(addition(limit - 1));
        var details = details("Creature", "A deck can have up to " + word + " cards named Example.", List.of("U"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details)).doesNotThrowAnyException();
        assertViolation(addition(2), details, "Copy limit");
    }

    @Test
    void unlimitedCopiesStillRespectTotalDeckSize() {
        addCommander();
        mockCommanderColors(List.of("U"));
        var details = details("Legendary Creature", "A deck can have any number of cards named Example.", List.of("U"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(99), details)).doesNotThrowAnyException();
        assertViolation(addition(100), details, "100 cards");
    }

    @Test
    void basicLandsCanRepeatButStillMustMatchCommanderColors() {
        addCommander();
        mockCommanderColors(List.of("U"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(30), details("Basic Land — Island", "", List.of("U"))))
                .doesNotThrowAnyException();
        assertViolation(addition(30), details("Basic Land — Forest", "", List.of("G")), "color identity");
    }

    @Test
    void allCardColorsMustBeContainedInCommanderIdentity() {
        addCommander();
        mockCommanderColors(List.of("U", "B"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details("Creature", "", List.of("B", "U"))))
                .doesNotThrowAnyException();
        assertViolation(addition(1), details("Creature", "", List.of("U", "R")), "color identity");
    }

    @Test
    void colorlessCardIsAllowedUnderColoredCommander() {
        addCommander();
        mockCommanderColors(List.of("U"));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details("Artifact", "", List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void colorlessCommanderAllowsOnlyColorlessIdentity() {
        addCommander();
        mockCommanderColors(List.of());
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details("Artifact", "", List.of())))
                .doesNotThrowAnyException();
        assertViolation(addition(1), details("Artifact", "", List.of("U")), "color identity");
    }

    @Test
    void usesColorIdentityEvenWhenTypeIsArtifactAndTextHasNoManaSymbols() {
        addCommander();
        mockCommanderColors(List.of("U"));
        assertViolation(addition(1), details("Artifact", "Flying", List.of("G")), "color identity");
    }

    @Test
    void acceptsHundredthCardAndRejectsHundredAndFirst() {
        addCommander();
        mockCommanderColors(List.of("U"));
        deck.addCard(new DeckCard(UUID.randomUUID(), 98, BoardType.MAINBOARD));
        assertThatCode(() -> validator.validateCardAddition(deck, addition(1), details("Creature", "", List.of("U"))))
                .doesNotThrowAnyException();
        assertViolation(addition(2), details("Basic Land", "", List.of("U")), "100 cards");
    }

    @Test
    void rejectsMainboardWithoutCommander() {
        assertViolation(addition(1), details("Creature", "", List.of()), "one or two distinct commanders");
        verifyNoInteractions(integration);
    }

    @Test
    void rejectsSecondCommanderAndCommanderQuantityOfTwoEvenWithOverride() {
        var details = details("Legendary Creature", "A deck can have any number of cards named Example.", List.of());
        assertViolation(new DeckCard(oracleId, 2, BoardType.COMMANDER), details, "one or two distinct commanders");
        addCommander();
        mockCommanderColors(List.of());
        assertViolation(new DeckCard(oracleId, 1, BoardType.COMMANDER), details, "compatible partner abilities");
    }

    @Test
    void addingCommanderChecksColorsOfExistingMainboard() {
        deck.addCard(addition(1));
        when(integration.fetchCardDetails(oracleId)).thenReturn(details("Creature", "", List.of("G")));
        assertViolation(new DeckCard(commanderId, 1, BoardType.COMMANDER), commanderDetails(List.of("U")), "color identity");
        assertThatCode(() -> validator.validateCardAddition(deck, new DeckCard(commanderId, 1, BoardType.COMMANDER),
                commanderDetails(List.of("U", "G")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsSideboardAndWrongFormat() {
        assertViolation(new DeckCard(oracleId, 1, BoardType.SIDEBOARD), details("Creature", "", List.of()), "sideboard");
        deck.setFormat(Format.MODERN);
        assertThat(validator.supports(Format.MODERN)).isFalse();
        assertViolation(addition(1), details("Creature", "", List.of()), "Unsupported");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantity(int quantity) {
        assertViolation(addition(quantity), details("Creature", "", List.of()), "positive");
    }

    @Test
    void countsCannotOverflowInteger() {
        addCommander();
        deck.addCard(new DeckCard(UUID.randomUUID(), Integer.MAX_VALUE, BoardType.MAINBOARD));
        assertViolation(addition(Integer.MAX_VALUE), details("Basic Land", "", List.of()), "100 cards");
    }

    @Test
    void integrationFailureDoesNotSilentlyBypassColorValidation() {
        addCommander();
        var failure = new CardManagerUnavailableException("Unavailable", null);
        when(integration.fetchCardDetails(commanderId)).thenThrow(failure);
        assertThatThrownBy(() -> validator.validateCardAddition(deck, addition(1), details("Creature", "", List.of())))
                .isSameAs(failure);
    }

    private void addCommander() {
        deck.addCard(new DeckCard(commanderId, 1, BoardType.COMMANDER));
    }

    private void mockCommanderColors(List<String> colors) {
        when(integration.fetchCardDetails(commanderId)).thenReturn(commanderDetails(colors));
    }

    private CardDetailsResponse commanderDetails(List<String> colors) {
        return new CardDetailsResponse(commanderId, "Commander", "Legendary Creature", "", colors, io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures.legalities());
    }

    private DeckCard addition(int quantity) {
        return new DeckCard(oracleId, quantity, BoardType.MAINBOARD);
    }

    private CardDetailsResponse details(String type, String text, List<String> colors) {
        return new CardDetailsResponse(oracleId, "Example", type, text, colors, io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures.legalities());
    }

    private void assertViolation(DeckCard card, CardDetailsResponse details, String message) {
        assertThatThrownBy(() -> validator.validateCardAddition(deck, card, details))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining(message);
    }
}

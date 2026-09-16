package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MultipleCommanderValidatorTest {
    @Mock CardIntegrationService integration;
    Deck deck = new Deck(1L,"Pair",Format.COMMANDER);
    UUID first=UUID.randomUUID(), second=UUID.randomUUID(), cardId=UUID.randomUUID();
    CommanderValidator validator() { return new CommanderValidator(new CardRuleOverrideService(), integration); }
    CardDetailsResponse card(UUID id, String text, List<String> colors) {
        return new CardDetailsResponse(id,id.toString(),"Legendary Creature",text,colors,CardTestFixtures.legalities(), java.util.List.of());
    }
    void pair() {
        deck.addCard(new DeckCard(first,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(second,1,BoardType.COMMANDER));
    }
    void metadata() {
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Partner",List.of("U")));
        when(integration.fetchCardDetails(second)).thenReturn(card(second,"Partner",List.of("R")));
    }
    @Test void mainboardUsesUnionOfBothIdentities() {
        pair(); metadata();
        assertThatCode(() -> validator().validateCardAddition(deck,new DeckCard(cardId,1,BoardType.MAINBOARD),card(cardId,"",List.of("U","R"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator().validateCardAddition(deck,new DeckCard(cardId,1,BoardType.MAINBOARD),card(cardId,"",List.of("G"))))
                .isInstanceOf(RuleViolationException.class);
    }
    @Test void ninetyEightMainboardMaximumEvenForUnlimitedCards() {
        pair(); metadata();
        var details=card(cardId,"A deck can have any number of cards named Example.",List.of("R"));
        assertThatCode(() -> validator().validateCardAddition(deck,new DeckCard(cardId,98,BoardType.MAINBOARD),details)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator().validateCardAddition(deck,new DeckCard(cardId,99,BoardType.MAINBOARD),details)).isInstanceOf(RuleViolationException.class);
    }
    @Test void thirdCommanderIsRejectedWithoutLookup() {
        pair();
        assertThatThrownBy(() -> validator().validateCardAddition(deck,new DeckCard(cardId,1,BoardType.COMMANDER),card(cardId,"Partner",List.of())))
                .isInstanceOf(RuleViolationException.class);
        verifyNoInteractions(integration);
    }
    @Test void secondCommanderCannotBeAddedToNinetyNineCardMainboard() {
        deck.addCard(new DeckCard(first,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(cardId,99,BoardType.MAINBOARD));
        assertThatThrownBy(() -> validator().validateCardAddition(deck,new DeckCard(second,1,BoardType.COMMANDER),card(second,"Partner",List.of())))
                .isInstanceOf(RuleViolationException.class);
    }
    @Test void completingDeckRequiresExactlyHundredWhileDraftCanBeSmaller() {
        pair();
        assertThatThrownBy(() -> validator().validateDeckCompletion(deck)).isInstanceOf(RuleViolationException.class);
        deck.addCard(new DeckCard(cardId,98,BoardType.MAINBOARD));
        assertThatCode(() -> validator().validateDeckCompletion(deck)).doesNotThrowAnyException();
        deck.getCards().getLast().setQuantity(99);
        assertThatThrownBy(() -> validator().validateDeckCompletion(deck)).isInstanceOf(RuleViolationException.class);
    }
    @Test void bansApplyToBothCommanders() {
        pair();
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Partner",List.of()));
        when(integration.fetchCardDetails(second)).thenReturn(new CardDetailsResponse(second,"Banned","Legendary Creature","Partner",List.of(),Map.of("commander",CardLegality.BANNED), java.util.List.of()));
        assertThatThrownBy(() -> validator().validateCardAddition(deck,new DeckCard(cardId,1,BoardType.MAINBOARD),card(cardId,"",List.of())))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("BANNED");
    }
}

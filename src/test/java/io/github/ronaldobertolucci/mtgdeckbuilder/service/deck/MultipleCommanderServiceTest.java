package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MultipleCommanderServiceTest {
    @Mock CardIntegrationService integration;
    @Mock DeckRepository repository;
    UUID first=UUID.randomUUID(),second=UUID.randomUUID(),cardId=UUID.randomUUID(),deckId=UUID.randomUUID();
    DeckService service() { return new DeckService(repository,integration,List.of(new CommanderValidator(new CardRuleOverrideService(),integration))); }
    CardDetailsResponse card(UUID id,String type,String text,List<String> colors) {
        return new CardDetailsResponse(id,id.toString(),type,text,colors,CardTestFixtures.legalities());
    }
    void saved() { when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0)); }
    @Test void backgroundFirstCanBeCreatedAtomically() {
        saved();
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Legendary Enchantment — Background","",List.of("U")));
        when(integration.fetchCardDetails(second)).thenReturn(card(second,"Legendary Creature","Choose a Background",List.of("R")));
        var result=service().create(1L,new CreateDeckRequest("Background",Format.COMMANDER,List.of(first,second)));
        assertThat(result.cards()).hasSize(2).allMatch(c -> c.boardType()==BoardType.COMMANDER && c.quantity()==1);
    }
    @Test void invalidPairDoesNotPersistAnything() {
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Legendary Creature","Partner",List.of()));
        when(integration.fetchCardDetails(second)).thenReturn(card(second,"Legendary Creature","Friends forever",List.of()));
        assertThatThrownBy(() -> service().create(1L,new CreateDeckRequest("Invalid",Format.COMMANDER,List.of(first,second))))
                .isInstanceOf(RuleViolationException.class);
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void removalMustPreserveRemainingColorIdentity() {
        var deck=new Deck(1L,"Pair",Format.COMMANDER);
        deck.addCard(new DeckCard(first,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(second,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(cardId,1,BoardType.MAINBOARD));
        when(repository.findOwnedForUpdate(deckId,1L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Legendary Creature","Partner",List.of("U")));
        when(integration.fetchCardDetails(cardId)).thenReturn(card(cardId,"Creature","",List.of("R")));
        assertThatThrownBy(() -> service().upsertCard(1L,deckId,new UpsertDeckCardRequest(second,BoardType.COMMANDER,0)))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("color identity");
        assertThat(deck.getCards()).hasSize(3);
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void compatibleRemovalIsAllowedAndKeepsMainboard() {
        saved();
        var deck=new Deck(1L,"Pair",Format.COMMANDER);
        deck.addCard(new DeckCard(first,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(second,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(cardId,1,BoardType.MAINBOARD));
        when(repository.findOwnedForUpdate(deckId,1L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(first)).thenReturn(card(first,"Legendary Creature","Partner",List.of("U")));
        when(integration.fetchCardDetails(cardId)).thenReturn(card(cardId,"Creature","",List.of("U")));
        assertThat(service().upsertCard(1L,deckId,new UpsertDeckCardRequest(second,BoardType.COMMANDER,0)).cards()).hasSize(2);
    }
    @Test void cannotRemoveChooserAndLeaveBackgroundAlone() {
        var deck=new Deck(1L,"Pair",Format.COMMANDER);
        deck.addCard(new DeckCard(first,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(second,1,BoardType.COMMANDER));
        when(repository.findOwnedForUpdate(deckId,1L)).thenReturn(Optional.of(deck));
        when(integration.fetchCardDetails(second)).thenReturn(card(second,"Legendary Enchantment — Background","",List.of()));
        assertThatThrownBy(() -> service().upsertCard(1L,deckId,new UpsertDeckCardRequest(first,BoardType.COMMANDER,0)))
                .isInstanceOf(RuleViolationException.class);
        assertThat(deck.getCards()).hasSize(2);
    }
}

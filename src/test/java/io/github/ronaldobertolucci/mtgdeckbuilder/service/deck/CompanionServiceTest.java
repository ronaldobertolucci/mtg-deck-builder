package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.UpsertDeckCardRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanionServiceTest {
    @Mock DeckRepository repository;
    @Mock CardIntegrationService integration;
    UUID deckId=UUID.randomUUID(),id=UUID.randomUUID();
    Deck deck=new Deck(1L,"Test",Format.MODERN);
    DeckService service() {return new DeckService(repository,integration,List.of(new Constructed60Validator(new CardRuleOverrideService()),new CommanderValidator(new CardRuleOverrideService(),integration)));}
    void owned() {when(repository.findOwnedForUpdate(deckId,1L)).thenReturn(Optional.of(deck));}
    void save() {when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));}
    CardDetailsResponse companion(UUID oracleId) {return new CardDetailsResponse(oracleId,"Companion","Creature","",List.of("R"),CardTestFixtures.legalities(),List.of("Companion"));}
    UpsertDeckCardRequest request(int q) {return new UpsertDeckCardRequest(id,BoardType.COMPANION,q);}

    @Test void addingAndUpdatingSameCompanionIsIdempotent() {
        owned();save();when(integration.fetchCardDetails(id)).thenReturn(companion(id));
        service().upsertCard(1L,deckId,request(1));
        DeckCard row=deck.getCards().getFirst();
        var result=service().upsertCard(1L,deckId,request(1));
        assertThat(deck.getCards()).containsExactly(row);
        assertThat(result.cards().getFirst().boardType()).isEqualTo(BoardType.COMPANION);
        assertThat(result.cards().getFirst().quantity()).isEqualTo(1);
    }
    @Test void secondCompanionDoesNotReplaceFirstOrPersist() {
        owned();deck.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.COMPANION));
        var original=deck.getCards().getFirst();
        when(integration.fetchCardDetails(id)).thenReturn(companion(id));
        assertThatThrownBy(() -> service().upsertCard(1L,deckId,request(1))).isInstanceOf(RuleViolationException.class);
        assertThat(deck.getCards()).containsExactly(original);
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void zeroRemovesCompanionOnlyWithoutMetadataLookup() {
        owned();save();deck.addCard(new DeckCard(id,1,BoardType.COMPANION));
        DeckCard main=new DeckCard(id,2,BoardType.MAINBOARD);deck.addCard(main);
        service().upsertCard(1L,deckId,request(0));service().upsertCard(1L,deckId,request(0));
        assertThat(deck.getCards()).containsExactly(main);verifyNoInteractions(integration);
    }
    @Test void removalReleasesSlotForDifferentCompanion() {
        owned();save();UUID old=UUID.randomUUID();deck.addCard(new DeckCard(old,1,BoardType.COMPANION));
        service().upsertCard(1L,deckId,new UpsertDeckCardRequest(old,BoardType.COMPANION,0));
        when(integration.fetchCardDetails(id)).thenReturn(companion(id));
        service().upsertCard(1L,deckId,request(1));
        assertThat(deck.getCards()).hasSize(1);assertThat(deck.getCards().getFirst().getOracleId()).isEqualTo(id);
    }
    @Test void removingPartnerCannotInvalidateCompanionColor() {
        owned();deck.setFormat(Format.COMMANDER);UUID blue=UUID.randomUUID(),red=UUID.randomUUID();
        deck.addCard(new DeckCard(blue,1,BoardType.COMMANDER));deck.addCard(new DeckCard(red,1,BoardType.COMMANDER));
        deck.addCard(new DeckCard(id,1,BoardType.COMPANION));
        when(integration.fetchCardDetails(blue)).thenReturn(new CardDetailsResponse(blue,"Blue","Legendary Creature","Partner",List.of("U"),CardTestFixtures.legalities(),List.of("Partner")));
        when(integration.fetchCardDetails(id)).thenReturn(companion(id));
        assertThatThrownBy(() -> service().upsertCard(1L,deckId,new UpsertDeckCardRequest(red,BoardType.COMMANDER,0)))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("color identity");
        assertThat(deck.getCards()).hasSize(3);verify(repository,never()).saveAndFlush(any());
    }
    @Test void lastCommanderCannotBeRemovedWhileCompanionRemains() {
        owned();deck.setFormat(Format.COMMANDER);UUID commander=UUID.randomUUID();
        deck.addCard(new DeckCard(commander,1,BoardType.COMMANDER));deck.addCard(new DeckCard(id,1,BoardType.COMPANION));
        assertThatThrownBy(() -> service().upsertCard(1L,deckId,new UpsertDeckCardRequest(commander,BoardType.COMMANDER,0)))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("companion");
        verifyNoInteractions(integration);
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanionValidatorTest {
    @Mock CardIntegrationService integration;
    UUID id=UUID.randomUUID(), leaderId=UUID.randomUUID();
    CardDetailsResponse details(List<String> keywords) {
        return new CardDetailsResponse(id,"Companion","Creature","Companion — Your starting deck contains only cards with even mana values.",
                List.of("U"),CardTestFixtures.legalities(),keywords);
    }
    FormatValidatorStrategy validator(Format format) {
        var overrides=new CardRuleOverrideService();
        return format==Format.COMMANDER ? new CommanderValidator(overrides,integration) : new Constructed60Validator(overrides);
    }
    Deck deck(Format format) {
        var deck=new Deck(1L,"Test",format);
        if(format==Format.COMMANDER) {
            deck.addCard(new DeckCard(leaderId,1,BoardType.COMMANDER));
            lenient().when(integration.fetchCardDetails(leaderId)).thenReturn(new CardDetailsResponse(leaderId,"Leader","Legendary Creature","",
                    List.of("U"),CardTestFixtures.legalities(),List.of()));
        }
        return deck;
    }
    void add(Deck deck,int quantity,BoardType board,CardDetailsResponse card) {
        validator(deck.getFormat()).validateCardAddition(deck,new DeckCard(card.oracleId(),quantity,board),card);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void singleCompanionIsAllowedAndConditionIsNotEvaluatedForMvp(Format format) {
        var deck=deck(format);
        deck.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.MAINBOARD));
        assertThatCode(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion")))).doesNotThrowAnyException();
        if(format==Format.COMMANDER) verify(integration).fetchCardDetails(leaderId);
        else verifyNoInteractions(integration);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void keywordMustBePresentNotJustOracleText(Format format) {
        for(var keywords:Arrays.asList(null,List.<String>of(),List.of("Flying"),List.of("Not Companion"))) {
            var deck=deck(format);
            assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,details(keywords)))
                    .isInstanceOf(RuleViolationException.class).hasMessageContaining("Companion keyword");
        }
    }

    @ParameterizedTest @EnumSource(Format.class)
    void onlyOneCompanionAndQuantityOne(Format format) {
        var deck=deck(format);
        assertThatThrownBy(() -> add(deck,2,BoardType.COMPANION,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("quantity 1");
        deck.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.COMPANION));
        assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("at most one companion");
    }

    @ParameterizedTest @EnumSource(value=Format.class,names={"STANDARD","MODERN","PIONEER","LEGACY"})
    void companionAndSideboardShareFifteenSlotsInEitherAdditionOrder(Format format) {
        var deck=deck(format);
        var side=new DeckCard(UUID.randomUUID(),14,BoardType.SIDEBOARD);deck.addCard(side);
        assertThatCode(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion")))).doesNotThrowAnyException();
        side.setQuantity(15);
        assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion")))).isInstanceOf(RuleViolationException.class);
        var reverse=deck(format);reverse.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.COMPANION));
        var basic=new CardDetailsResponse(id,"Island","Basic Land","",List.of("U"),CardTestFixtures.legalities(),List.of());
        assertThatCode(() -> add(reverse,14,BoardType.SIDEBOARD,basic)).doesNotThrowAnyException();
        assertThatThrownBy(() -> add(reverse,15,BoardType.SIDEBOARD,basic)).isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest @EnumSource(value=Format.class,names={"STANDARD","MODERN","PIONEER","LEGACY"})
    void copyLimitCountsMainSideAndCompanionInBothDirections(Format format) {
        var deck=deck(format);
        deck.addCard(new DeckCard(id,3,BoardType.MAINBOARD));
        assertThatCode(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion")))).doesNotThrowAnyException();
        deck.addCard(new DeckCard(id,1,BoardType.SIDEBOARD));
        assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
        var reverse=deck(format);reverse.addCard(new DeckCard(id,1,BoardType.COMPANION));
        assertThatCode(() -> add(reverse,3,BoardType.MAINBOARD,details(List.of("Companion")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> add(reverse,4,BoardType.MAINBOARD,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
    }

    @Test void commanderCanHaveOneHundredCardsPlusCompanionAndNoSideboard() {
        var deck=deck(Format.COMMANDER);deck.addCard(new DeckCard(UUID.randomUUID(),99,BoardType.MAINBOARD));
        assertThatCode(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion")))).doesNotThrowAnyException();
        deck.addCard(new DeckCard(id,1,BoardType.COMPANION));
        assertThatCode(() -> ((CommanderValidator)validator(Format.COMMANDER)).validateDeckCompletion(deck)).doesNotThrowAnyException();
        assertThatThrownBy(() -> add(deck,1,BoardType.SIDEBOARD,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("sideboard");
    }

    @Test void commanderSingletonCountsCompanionInBothDirections() {
        var deck=deck(Format.COMMANDER);deck.addCard(new DeckCard(id,1,BoardType.MAINBOARD));
        assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
        var reverse=deck(Format.COMMANDER);reverse.addCard(new DeckCard(id,1,BoardType.COMPANION));
        assertThatThrownBy(() -> add(reverse,1,BoardType.MAINBOARD,details(List.of("Companion"))))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("Copy limit");
    }

    @Test void companionMustFitCommanderColorIdentity() {
        var deck=deck(Format.COMMANDER);
        var offColor=new CardDetailsResponse(id,"Companion","Creature","",List.of("R"),CardTestFixtures.legalities(),List.of("Companion"));
        assertThatThrownBy(() -> add(deck,1,BoardType.COMPANION,offColor))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("color identity");
    }

    @ParameterizedTest @EnumSource(Format.class)
    void bannedAndNotLegalCompanionsCannotBeSelected(Format format) {
        for(var legality:List.of(CardLegality.BANNED,CardLegality.NOT_LEGAL)) {
            var card=new CardDetailsResponse(id,"Illegal","Creature","",List.of(),Map.of(format.name(),legality),List.of("Companion"));
            assertThatThrownBy(() -> add(deck(format),1,BoardType.COMPANION,card))
                    .isInstanceOf(RuleViolationException.class).hasMessageContaining(legality.name());
        }
    }

    @Test void companionKeywordDoesNotForceUseOfCompanionZone() {
        assertThatCode(() -> add(deck(Format.MODERN),4,BoardType.MAINBOARD,details(List.of("Companion")))).doesNotThrowAnyException();
    }

    @Test void completionRejectsTwoCompanions() {
        var deck=deck(Format.COMMANDER);deck.addCard(new DeckCard(UUID.randomUUID(),99,BoardType.MAINBOARD));
        deck.addCard(new DeckCard(id,1,BoardType.COMPANION));deck.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.COMPANION));
        assertThatThrownBy(() -> ((CommanderValidator)validator(Format.COMMANDER)).validateDeckCompletion(deck)).isInstanceOf(RuleViolationException.class);
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CompanionRequirementRulesTest {
    private static final Map<String, UUID> IDS = Map.ofEntries(Map.entry("GYRUDA", UUID.fromString("25eaa977-ca85-4f8d-8be5-9297a3edb14f")),
            Map.entry("JEGANTHA", UUID.fromString("f0587c55-06c1-4930-a62e-668f37464ce9")),
            Map.entry("KAHEERA", UUID.fromString("35428c77-9731-4d4d-8f75-e939a491cf86")),
            Map.entry("KERUGA", UUID.fromString("ca5121b2-e6d9-40bc-a2d2-05852c4efbe8")),
            Map.entry("LURRUS", UUID.fromString("3bc757c1-3adb-4321-8832-8e1cc9e687f7")),
            Map.entry("LUTRI", UUID.fromString("158a6225-a246-4fd6-aa57-0df8067b4383")),
            Map.entry("OBOSH", UUID.fromString("6985c8a9-9299-461b-8bef-bd2b2c739c11")),
            Map.entry("UMORI", UUID.fromString("913383a1-9c0d-40a1-8c5a-b0a8a95c09a6")),
            Map.entry("YORION", UUID.fromString("e15504a7-2b67-4185-b916-172145f10b19")),
            Map.entry("ZIRDA", UUID.fromString("7b515c56-7b1e-42a8-82b0-1b30201fb162")));
    private final Deck deck = new Deck(1L, "Test", Format.MODERN);
    private final Map<UUID, CardDetailsResponse> cards = new HashMap<>();
    private final List<AnalysisReason> violations = new ArrayList<>(), uncertainties = new ArrayList<>();

    private void companion(String rule) { deck.addCard(new DeckCard(IDS.get(rule), 1, BoardType.COMPANION)); }
    private CardDetailsResponse card(String type, double cmc, String mana, String text) {
        return new CardDetailsResponse(UUID.randomUUID(), "Card", type, text, List.of(), Map.of(),
                List.of(), cmc, mana, "rare");
    }
    private void add(CardDetailsResponse card, int count, BoardType board) {
        deck.addCard(new DeckCard(card.oracleId(), count, board)); cards.put(card.oracleId(), card);
    }
    private void evaluate() { CompanionRequirementRules.evaluate(deck, cards, violations, uncertainties); }

    @ParameterizedTest
    @CsvSource({
        "GYRUDA,Creature,2,{1}{G},true", "GYRUDA,Creature,3,{2}{G},false",
        "OBOSH,Creature,3,{2}{G},true", "OBOSH,Creature,0,{X},false",
        "KERUGA,Creature,3,{2}{G},true", "KERUGA,Creature,2,{1}{G},false",
        "LURRUS,Artifact,2,{2},true", "LURRUS,Battle,3,{3},false", "LURRUS,Instant,6,{6},true",
        "JEGANTHA,Creature,3,{1}{G}{G/U},true", "JEGANTHA,Creature,3,{1}{G}{G},false",
        "JEGANTHA,Creature,0,{X}{X},false", "JEGANTHA,Creature,4,{2}{2},false",
        "KAHEERA,Creature — Cat,2,{1}{G},true", "KAHEERA,Creature — Human,2,{1}{G},false",
        "KAHEERA,Instant,2,{1}{G},true", "ZIRDA,Artifact,2,{2},false", "ZIRDA,Instant,2,{2},true"
    })
    void individualConditions(String rule, String type, double cmc, String mana, boolean valid) {
        companion(rule); add(card(type,cmc,mana,""),1,BoardType.MAINBOARD); evaluate();
        assertThat(uncertainties).isEmpty(); assertThat(violations).hasSize(valid ? 0 : 1);
        if (!valid) assertThat(violations.getFirst().getParameters()).containsKey("companionOracleId").containsKey("oracleId");
    }

    @ParameterizedTest @CsvSource({"GYRUDA", "JEGANTHA", "KAHEERA", "KERUGA", "LURRUS", "LUTRI", "OBOSH", "UMORI", "ZIRDA"})
    void landsAndOutsideZones(String rule) {
        companion(rule);
        add(card("Basic Land — Forest",0,"",""),60,BoardType.MAINBOARD);
        for (var board : List.of(BoardType.SIDEBOARD,BoardType.TOKENS))
            add(card("Creature — Human",7,"{G}{G}",""),4,board);
        evaluate(); assertThat(violations).isEmpty(); assertThat(uncertainties).isEmpty();
    }

    @ParameterizedTest @CsvSource({"GYRUDA", "JEGANTHA", "KAHEERA", "KERUGA", "LURRUS", "OBOSH", "ZIRDA"})
    void commandersAreChecked(String rule) {
        companion(rule); deck.setFormat(Format.COMMANDER);
        double cmc = rule.equals("GYRUDA") || rule.equals("LURRUS") ? 3 : 2;
        add(card("Legendary Creature — Human",cmc,"{G}{G}",""),1,BoardType.COMMANDER);
        evaluate(); assertThat(violations).hasSize(1);
    }

    @ParameterizedTest @CsvSource({"79,false", "80,true", "81,true"})
    void yorionBoundary(int size, boolean valid) {
        companion("YORION"); add(card("Land",0,"",""),size,BoardType.MAINBOARD);
        add(card("Land",0,"",""),15,BoardType.SIDEBOARD); evaluate();
        assertThat(violations).hasSize(valid ? 0 : 1);
    }
    @Test void yorionCannotFitCommander() {
        companion("YORION"); deck.setFormat(Format.COMMANDER);
        add(card("Land",0,"",""),99,BoardType.MAINBOARD);
        add(card("Legendary Creature",2,"{2}",""),1,BoardType.COMMANDER);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void lutriRejectsCopiesEvenWithCopyOverride() {
        companion("LUTRI"); add(card("Creature",2,"{2}","A deck can have any number of cards named Card."),2,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void lutriChecksNamesAcrossOracleIdsAndCommander() {
        companion("LUTRI"); add(card("Creature",2,"{2}",""),1,BoardType.MAINBOARD);
        add(card("Creature",2,"{2}",""),1,BoardType.COMMANDER);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void umoriRequiresGlobalIntersectionNotPairwiseOverlap() {
        companion("UMORI");
        for (String type : List.of("Artifact Creature", "Enchantment Creature", "Artifact Enchantment"))
            add(card(type,2,"{2}",""),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void umoriAcceptsSharedTypeAndIgnoresSupertypes() {
        companion("UMORI"); add(card("Legendary Artifact Creature",2,"{2}",""),1,BoardType.MAINBOARD);
        add(card("Enchantment Creature",2,"{2}",""),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).isEmpty();
        add(card("Legendary Sorcery",2,"{2}",""),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void changelingSatisfiesKaheera() {
        companion("KAHEERA"); add(card("Creature — Shapeshifter",2,"{2}","Changeling"),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).isEmpty();
    }
    @ParameterizedTest @CsvSource({"Equip {2},true", "Cycling {2},true", "Forestcycling {2},true",
        "{T}: Add {G}.,true", "Flying,false", "Morph {3},false", "When this enters draw a card.,false",
        "Other creatures have cycling.,false"})
    void zirdaActivatedAbilities(String text, boolean valid) {
        companion("ZIRDA"); add(card("Artifact",2,"{2}",text),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(valid ? 0 : 1);
    }
    @Test void grantedAbilityDoesNotSatisfyZirda() {
        companion("ZIRDA"); add(card("Enchantment",2,"{2}","Creatures you control have \"{T}: Add {G}.\""),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"KAHEERA", "JEGANTHA", "ZIRDA"})
    void frontFaceDeterminesTypesAndAbilities(String rule) {
        companion(rule);
        var front = new CardFaceResponse("Front","Creature — Cat","{T}: Add {G}.","{2}");
        var back = new CardFaceResponse("Back","Creature — Human","","{G}{G}");
        var card = new CardDetailsResponse(UUID.randomUUID(),"Front // Back","Creature — Cat // Creature — Human",null,
                List.of(),Map.of(),List.of(),2.0,null,"rare",List.of(),"modal_dfc",List.of(),List.of(front,back));
        add(card,1,BoardType.MAINBOARD); evaluate(); assertThat(violations).isEmpty(); assertThat(uncertainties).isEmpty();
    }
    @Test void splitCostsAreCombinedForJegantha() {
        companion("JEGANTHA");
        var card = new CardDetailsResponse(UUID.randomUUID(),"Front // Back","Instant // Sorcery","",
                List.of(),Map.of(),List.of(),4.0,null,"rare",List.of(),"split",List.of(),
                List.of(new CardFaceResponse("Front","Instant","","{1}{G}"),new CardFaceResponse("Back","Sorcery","","{1}{R}")));
        add(card,1,BoardType.MAINBOARD); evaluate(); assertThat(violations).hasSize(1);
    }
    @Test void missingManaValueIsUncertaintyButKnownViolationSurvives() {
        companion("GYRUDA");
        add(new CardDetailsResponse(UUID.randomUUID(),"Unknown","Creature","",List.of(),Map.of(),List.of()),1,BoardType.MAINBOARD);
        add(card("Creature",3,"{3}",""),1,BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1); assertThat(uncertainties).hasSize(1);
    }
    @Test void unknownCompanionHasExplicitReason() {
        deck.addCard(new DeckCard(UUID.randomUUID(),1,BoardType.COMPANION)); evaluate();
        assertThat(violations).isEmpty(); assertThat(uncertainties).singleElement().satisfies(r -> {
            assertThat(r.getCode()).isEqualTo("COMPANION_REQUIREMENTS_NOT_EVALUATED");
            assertThat(r.getMessage()).contains("10 supported companions", "new validator");
        });
    }
    @ParameterizedTest
    @CsvSource({
        "Basic landcycling {1},true", "Craft with artifact {4}{W}{W},true",
        "Equip legendary creature {1},true", "Commander ninjutsu {U}{B},true",
        "Station,true", "Reconfigure {2},true", "Transmute {1}{U}{U},true",
        "Unearth {2}{B},true", "Scavenge {3}{G},true", "Embalm {2}{W},true",
        "Eternalize {4}{W}{W},true", "Encore {5}{U},true", "Reinforce 1—{1}{W},true",
        "Disguise {3},false", "Plot {2},false", "Suspend 3—{U},false",
        "Creatures you control have cycling {2}.,false",
        "Equip abilities you activate cost {1} less to activate.,false",
        "Adapt 2.,false", "Monstrosity 3.,false",
        "Solved — {T}: Draw a card.,true", "Exhaust — {2}: Draw a card.,true"
    })
    void zirdaDistinguishesKeywordsActionsAndGrantedAbilities(String text, boolean valid) {
        companion("ZIRDA"); add(card("Artifact", 2, "{2}", text), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(uncertainties).isEmpty(); assertThat(violations).hasSize(valid ? 0 : 1);
    }

    @ParameterizedTest
    @CsvSource({
        "This creature is every creature type (even if this card isn't on the battlefield).,true",
        "Card is every creature type.,true",
        "Changeling (This card is every creature type.),true",
        "Target creature is every creature type until end of turn.,false",
        "As long as you control an artifact this creature is every creature type.,false",
        "Changeling abilities of other creatures have no effect.,false"
    })
    void kaheeraRequiresItsOwnCharacteristicDefiningAbility(String text, boolean valid) {
        companion("KAHEERA"); add(card("Creature — Shapeshifter", 2, "{2}", text), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(uncertainties).isEmpty(); assertThat(violations).hasSize(valid ? 0 : 1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"modal_dfc", "transform", "flip", "adventure", "omen", "prepare"})
    void alternativeCharacteristicsCannotSatisfyCompanion(String layout) {
        companion("KAHEERA");
        var front = new CardFaceResponse("Front", "Creature — Human", "", "{1}{U}");
        var back = new CardFaceResponse("Back", "Creature — Cat", "Changeling", "{U}");
        var card = new CardDetailsResponse(UUID.randomUUID(), "Front // Back", "Creature — Human // Creature — Cat",
                "Changeling", List.of(), Map.of(), List.of(), 2.0, "{1}{U}", "rare", List.of(), layout, List.of(), List.of(front, back));
        add(card, 1, BoardType.MAINBOARD); evaluate();
        assertThat(violations).hasSize(1); assertThat(uncertainties).isEmpty();
    }

    @Test void unsupportedMultifaceLayoutDoesNotSilentlyPass() {
        companion("KAHEERA");
        var card = new CardDetailsResponse(UUID.randomUUID(), "Front // Back", "Creature — Cat", "",
                List.of(), Map.of(), List.of(), 2.0, "{2}", "rare", List.of(), "future_layout", List.of(),
                List.of(new CardFaceResponse("Front", "Creature — Human", "", "{2}")));
        add(card, 1, BoardType.MAINBOARD); evaluate();
        assertThat(violations).isEmpty(); assertThat(uncertainties).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"GYRUDA,4,true", "GYRUDA,5,false", "OBOSH,4,false", "OBOSH,5,true",
        "KERUGA,4,true", "LURRUS,4,false"})
    void splitPermanentUsesCombinedManaValue(String rule, double cmc, boolean valid) {
        companion(rule);
        var card = new CardDetailsResponse(UUID.randomUUID(), "Left // Right", "Enchantment — Room", "",
                List.of(), Map.of(), List.of(), cmc, "{1}{U} // {1}{R}", "rare", List.of(), "split", List.of(),
                List.of(new CardFaceResponse("Left", "Enchantment — Room", "", "{1}{U}"),
                        new CardFaceResponse("Right", "Enchantment — Room", "", "{1}{R}")));
        add(card, 1, BoardType.MAINBOARD); evaluate(); assertThat(violations).hasSize(valid ? 0 : 1);
    }

    @Test void roomUnlockIsNotAnActivatedAbility() {
        companion("ZIRDA"); add(card("Enchantment — Room", 4, "{1}{U} // {1}{R}",
                "When you unlock this door, draw a card.\n(You may pay a locked half's mana cost to unlock it.)"), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }

    @Test void prototypeUsesNormalManaCost() {
        companion("LURRUS"); add(card("Artifact Creature — Golem", 7, "{7}",
                "Prototype {1}{W} — 2/2 (You may cast this spell with different characteristics.)"), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }

    @Test void lutriDetectsNamesSharedWithSplitCards() {
        companion("LUTRI");
        var split = new CardDetailsResponse(UUID.randomUUID(), "Card // Other", "Instant // Sorcery", "",
                List.of(), Map.of(), List.of(), 4.0, "{2} // {2}", "rare", List.of(), "split", List.of());
        add(split, 1, BoardType.MAINBOARD); add(card("Instant", 2, "{2}", ""), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(violations).hasSize(1);
    }

    @Test void umoriCanShareKindredButCannotShareLegendary() {
        companion("UMORI");
        add(card("Kindred Instant — Elf", 2, "{2}", ""), 1, BoardType.MAINBOARD);
        add(card("Tribal Enchantment — Goblin", 2, "{2}", ""), 1, BoardType.MAINBOARD);
        evaluate(); assertThat(violations).isEmpty();
    }

}

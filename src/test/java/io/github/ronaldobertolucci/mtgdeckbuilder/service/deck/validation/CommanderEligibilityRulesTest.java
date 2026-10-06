package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.*;

class CommanderEligibilityRulesTest {
    private CardDetailsResponse card(String type, String text, CardLegality legality) {
        return new CardDetailsResponse(UUID.randomUUID(), "Example", type, text, List.of(),
                Map.of("commander", legality), List.of());
    }
    private CardDetailsResponse faced(String layout, CardFaceResponse front, CardFaceResponse back) {
        return new CardDetailsResponse(UUID.randomUUID(), "Front // Back", "Legendary Creature // Legendary Planeswalker",
                "Root can be your commander.", List.of("U", "G"), Map.of("commander", CardLegality.LEGAL),
                List.of("Partner"), null, null, null, List.of(), layout, List.of(), Arrays.asList(front, back));
    }
    private void rejects(CardDetailsResponse card, RuleErrorCode code) {
        assertThatThrownBy(() -> CommanderEligibilityRules.validate(card)).isInstanceOfSatisfying(
                RuleViolationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(code);
                    assertThat(ex.getField()).isEqualTo("commanderOracleIds");
                    assertThat(ex.getOracleIds()).containsExactly(card.oracleId());
                });
    }
    @Test void acceptsLegendaryCreatureIncludingArtifactCreature() {
        assertThatCode(() -> CommanderEligibilityRules.validate(card("Legendary Artifact Creature — Golem", "", CardLegality.LEGAL)))
                .doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings = {"Creature — Human", "Legendary Enchantment", "Legendary Planeswalker — Example"})
    void rejectsOtherTypesWithoutPermission(String type) { rejects(card(type, "", CardLegality.LEGAL), COMMANDER_NOT_ELIGIBLE); }
    @Test void acceptsExplicitPermissionButNotQuotedOrOtherCardPermission() {
        assertThatCode(() -> CommanderEligibilityRules.validate(card("Legendary Planeswalker — Example",
                "+1: Draw a card.\nExample can be your commander.", CardLegality.LEGAL))).doesNotThrowAnyException();
        rejects(card("Legendary Planeswalker — Example", "Other can be your commander.", CardLegality.LEGAL), COMMANDER_NOT_ELIGIBLE);
        rejects(card("Legendary Planeswalker — Example", "Creatures have \"Example can be your commander.\"", CardLegality.LEGAL), COMMANDER_NOT_ELIGIBLE);
    }
    @Test void banPrecedesTypesPermissionAndIncompleteFaces() {
        rejects(card("Legendary Creature", "Example can be your commander.", CardLegality.BANNED), CARD_BANNED);
        var banned = new CardDetailsResponse(UUID.randomUUID(), "Missing", null, null, List.of(),
                Map.of("commander", CardLegality.BANNED), List.of(), null, null, null, List.of(), "modal_dfc", List.of());
        rejects(banned, CARD_BANNED);
    }
    @Test void rejectsNotLegalAndMissingLegality() {
        rejects(card("Legendary Creature", "", CardLegality.NOT_LEGAL), CARD_NOT_LEGAL);
        rejects(card("Legendary Creature", "", CardLegality.UNKNOWN), CARD_LEGALITY_UNKNOWN);
        rejects(new CardDetailsResponse(UUID.randomUUID(), "Example", "Legendary Creature", "", List.of(), null, List.of()), CARD_LEGALITY_UNKNOWN);
    }
    @ParameterizedTest @ValueSource(strings = {"modal_dfc", "transform", "flip", "adventure"})
    void frontDeterminesEligibilityWithoutCombiningFaces(String layout) {
        var creature = new CardFaceResponse("Front", "Legendary Creature — God", "");
        var land = new CardFaceResponse("Back", "Land", "");
        assertThatCode(() -> CommanderEligibilityRules.validate(faced(layout, creature, land))).doesNotThrowAnyException();
        rejects(faced(layout, land, creature), COMMANDER_NOT_ELIGIBLE);
        rejects(faced(layout, new CardFaceResponse("Front", "Legendary Enchantment", ""),
                new CardFaceResponse("Back", "Creature", "")), COMMANDER_NOT_ELIGIBLE);
    }
    @Test void permissionAndPartnerOnlyOnBackDoNotApply() {
        var front = new CardFaceResponse("Front", "Legendary Planeswalker", "");
        var back = new CardFaceResponse("Back", "Legendary Planeswalker", "Back can be your commander.\nPartner");
        rejects(faced("modal_dfc", front, back), COMMANDER_NOT_ELIGIBLE);
        var partnerFront = new CardFaceResponse("Front", "Legendary Creature", "");
        assertThatThrownBy(() -> CommanderPairRules.validate(List.of(faced("modal_dfc", partnerFront, back),
                card("Legendary Creature", "Partner", CardLegality.LEGAL))))
                .isInstanceOfSatisfying(RuleViolationException.class, ex -> assertThat(ex.getCode()).isEqualTo(INCOMPATIBLE_COMMANDER_PAIR));
    }
    @Test void partnerWithUsesFrontFaceNameAndAbility() {
        var a = faced("modal_dfc", new CardFaceResponse("Front", "Legendary Creature", "Partner with Example"),
                new CardFaceResponse("Back", "Land", ""));
        var b = card("Legendary Creature", "Partner with Front", CardLegality.LEGAL);
        assertThatCode(() -> CommanderPairRules.validate(List.of(a, b))).doesNotThrowAnyException();
        assertThatCode(() -> CommanderPairRules.validate(List.of(b, a))).doesNotThrowAnyException();
    }
    @Test void rejectsMissingFrontWithoutFallingBackToRoot() {
        rejects(faced("modal_dfc", null, new CardFaceResponse("Back", "Legendary Creature", "")), COMMANDER_DATA_INCOMPLETE);
        rejects(new CardDetailsResponse(UUID.randomUUID(), "Example", "Legendary Creature", "", List.of(),
                Map.of("commander", CardLegality.LEGAL), List.of(), null, null, null, List.of(), "modal_dfc", List.of()), COMMANDER_DATA_INCOMPLETE);
    }
    @Test void backgroundStillRequiresCompatibleChooser() {
        rejects(card("Legendary Enchantment — Background", "", CardLegality.LEGAL), COMMANDER_NOT_ELIGIBLE);
    }
    @ParameterizedTest @ValueSource(strings = {"esika", "jace", "normal"})
    void acceptsRealCardManagerFixtures(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/cards/" + name + ".json")) {
            var card = JsonMapper.builder().build().readValue(stream, CardDetailsResponse.class);
            assertThatCode(() -> CommanderEligibilityRules.validate(card)).doesNotThrowAnyException();
            if (!card.cardFaces().isEmpty()) assertThat(card.oracleText()).isNull();
        }
    }
    @Test void rejectsRealInvasionWithLegendaryCreatureOnlyOnBack() throws Exception {
        try (var stream = getClass().getResourceAsStream("/cards/invasion.json")) {
            var card = JsonMapper.builder().build().readValue(stream, CardDetailsResponse.class);
            rejects(card, COMMANDER_NOT_ELIGIBLE);
        }
    }
}

package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CommanderPairRulesTest {
    private CardDetailsResponse card(String name, String type, String text) {
        return new CardDetailsResponse(UUID.randomUUID(), name, type, text, List.of(), CardTestFixtures.legalities());
    }
    private CardDetailsResponse creature(String name, String text) { return card(name, "Legendary Creature — Human", text); }
    private void acceptsBothOrders(CardDetailsResponse a, CardDetailsResponse b) {
        assertThatCode(() -> CommanderPairRules.validate(List.of(a,b))).doesNotThrowAnyException();
        assertThatCode(() -> CommanderPairRules.validate(List.of(b,a))).doesNotThrowAnyException();
    }
    private void rejects(CardDetailsResponse a, CardDetailsResponse b) {
        assertThatThrownBy(() -> CommanderPairRules.validate(List.of(a,b))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> CommanderPairRules.validate(List.of(b,a))).isInstanceOf(RuleViolationException.class);
    }
    @Test void partnersWithReminderText() {
        acceptsBothOrders(creature("A", "Flying\nPartner (You can have two commanders if both have partner.)"), creature("B", "Partner"));
    }
    @Test void namedPartnersRequireReciprocalExactNames() {
        acceptsBothOrders(creature("A, the First", "Partner with B, the Second (Reminder.)"),
                creature("B, the Second", "Partner with A, the First"));
        rejects(creature("A", "Partner with B"),creature("B", "Partner with C"));
        rejects(creature("A", "Partner with B"),creature("B", "Partner"));
    }
    @Test void friendsForeverIncludingCurrentOracleWording() {
        acceptsBothOrders(creature("A", "Friends forever"), creature("B", "Friends forever"));
        acceptsBothOrders(creature("A", "Partner—Friends forever"), creature("B", "Friends forever"));
        rejects(creature("A", "Friends forever"), creature("B", "Partner"));
    }
    @Test void doctorsCompanionRequiresExactlyTimeLordDoctor() {
        var companion = creature("Companion", "Doctor’s companion (Reminder.)");
        acceptsBothOrders(companion,card("Doctor", "Legendary Creature — Time Lord Doctor", ""));
        rejects(companion,card("Doctor", "Legendary Creature — Human Time Lord Doctor", ""));
        rejects(companion,card("Doctor", "Creature — Time Lord Doctor", ""));
        rejects(companion,creature("Other", "Doctor's companion"));
    }
    @Test void backgroundRequiresChooserAndLegendaryEnchantment() {
        var chooser=creature("Chooser", "Choose a Background");
        acceptsBothOrders(chooser,card("Background", "Legendary Enchantment — Background", ""));
        rejects(chooser,card("Background", "Enchantment — Background", ""));
        rejects(chooser,card("Other", "Legendary Enchantment", ""));
        rejects(creature("A", "Partner"),card("Background", "Legendary Enchantment — Background", ""));
        assertThatThrownBy(() -> CommanderPairRules.validate(List.of(card("Background", "Legendary Enchantment — Background", ""))))
                .isInstanceOf(RuleViolationException.class);
    }
    @ParameterizedTest @CsvSource({"Partner,Friends forever", "Choose a Background,Partner", "Doctor's companion,Partner"})
    void mechanicsCannotBeMixed(String a, String b) { rejects(creature("A", a),creature("B", b)); }
    @Test void keywordsMentionedInRulesAreNotActualAbilities() {
        rejects(creature("A", "Creatures you control have partner."), creature("B", "Partner"));
        rejects(creature("A", "(This card does not have Partner.)"), creature("B", "Partner"));
    }
    @Test void duplicatesAndIneligibleCommandersFail() {
        var same=creature("A", "Partner");
        rejects(same,same);
        rejects(card("A","Creature", "Partner"),creature("B","Partner"));
        assertThatThrownBy(() -> CommanderPairRules.validate(List.of(card("Raptor","Creature — Dinosaur", ""))))
                .isInstanceOf(RuleViolationException.class);
    }
    @Test void planeswalkerWithExplicitPermissionAndPartnerIsAllowed() {
        acceptsBothOrders(card("Walker", "Legendary Planeswalker — Walker", "Walker can be your commander.\nPartner"), creature("B", "Partner"));
    }
}

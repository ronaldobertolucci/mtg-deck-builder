package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import java.util.*;
import java.util.regex.Pattern;

final class CommanderPairRules {
    private CommanderPairRules() {}

    static void validate(List<CardDetailsResponse> commanders) {
        if (commanders.size() == 1) {
            if (!canLead(commanders.getFirst()) || background(commanders.getFirst())) {
                throw new RuleViolationException("Card is not eligible to be the sole commander");
            }
            return;
        }
        var first = commanders.get(0);
        var second = commanders.get(1);
        if (first.oracleId().equals(second.oracleId())) {
            throw new RuleViolationException("Commanders must be distinct cards");
        }
        if (!type(first, "legendary") || !type(second, "legendary")) {
            throw new RuleViolationException("Both commanders must be legendary cards");
        }
        if (backgroundPair(first, second) || backgroundPair(second, first)) return;
        if (canLead(first) && canLead(second)) {
            if (ability(first, "partner") && ability(second, "partner")) return;
            if (friends(first) && friends(second)) return;
            if (partnerWith(first, second) && partnerWith(second, first)) return;
            if ((ability(first, "doctor's companion") && legendaryCreature(first) && doctor(second))
                    || (ability(second, "doctor's companion") && legendaryCreature(second) && doctor(first))) return;
        }
        throw new RuleViolationException("The two commanders do not have compatible partner abilities");
    }

    private static boolean backgroundPair(CardDetailsResponse chooser, CardDetailsResponse other) {
        return canLead(chooser) && ability(chooser, "choose a background") && background(other);
    }

    private static boolean friends(CardDetailsResponse card) {
        return ability(card, "friends forever") || ability(card, "partner—friends forever");
    }

    private static boolean partnerWith(CardDetailsResponse card, CardDetailsResponse other) {
        return ability(card, "partner with " + normalize(other.name()));
    }

    private static boolean ability(CardDetailsResponse card, String keyword) {
        // Match actual ability lines, not keywords quoted in other rules or reminder text.
        String text = normalize(card.oracleText()).replaceAll("(?s)\\([^)]*\\)", "");
        return text.lines().anyMatch(line -> line.strip().replaceAll("[.]$", "").equals(keyword));
    }

    private static boolean canLead(CardDetailsResponse card) {
        return legendaryCreature(card) || normalize(card.oracleText()).contains("can be your commander");
    }

    private static boolean legendaryCreature(CardDetailsResponse card) {
        return type(card, "legendary") && type(card, "creature");
    }

    private static boolean background(CardDetailsResponse card) {
        return type(card, "legendary") && type(card, "enchantment") && type(card, "background");
    }

    private static boolean doctor(CardDetailsResponse card) {
        String[] parts = normalize(card.typeLine()).split("—", 2);
        return legendaryCreature(card) && parts.length == 2 && parts[1].strip().equals("time lord doctor");
    }

    private static boolean type(CardDetailsResponse card, String word) {
        return Pattern.compile("\\b" + word + "\\b").matcher(normalize(card.typeLine())).find();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[ \t]*[—–][ \t]*", "—");
    }
}

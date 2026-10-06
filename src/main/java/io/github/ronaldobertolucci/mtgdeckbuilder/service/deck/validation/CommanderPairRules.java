package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import java.util.*;
import static io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.CommanderEligibilityRules.*;
import static io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.*;

final class CommanderPairRules {
    private CommanderPairRules() {}

    static void validate(List<CardDetailsResponse> commanders) {
        if (commanders == null || commanders.isEmpty() || commanders.size() > 2) {
            throw new RuleViolationException(INVALID_COMMANDER_SELECTION, "Invalid commander selection",
                    "commanderOracleIds", List.of());
        }
        if (commanders.size() == 1) {
            if (!canLead(commanders.getFirst()) || background(commanders.getFirst())) {
                throw rejection(COMMANDER_NOT_ELIGIBLE, "Card is not eligible to be the sole commander", commanders.getFirst());
            }
            return;
        }
        var first = commanders.get(0);
        var second = commanders.get(1);
        if (first.oracleId().equals(second.oracleId())) {
            throw pairError(INVALID_COMMANDER_SELECTION, "Commanders must be distinct cards", commanders);
        }
        if (!type(first, "legendary") || !type(second, "legendary")) {
            throw pairError(COMMANDER_NOT_ELIGIBLE, "Both commanders must be legendary cards", commanders);
        }
        if (backgroundPair(first, second) || backgroundPair(second, first)) return;
        if (canLead(first) && canLead(second)) {
            if (ability(first, "partner") && ability(second, "partner")) return;
            if (friends(first) && friends(second)) return;
            if (partnerWith(first, second) && partnerWith(second, first)) return;
            if ((ability(first, "doctor's companion") && legendaryCreature(first) && doctor(second))
                    || (ability(second, "doctor's companion") && legendaryCreature(second) && doctor(first))) return;
        }
        if ((!canLead(first) && !background(first)) || (!canLead(second) && !background(second))) {
            throw pairError(COMMANDER_NOT_ELIGIBLE, "Card is not eligible to be a commander", commanders);
        }
        throw pairError(INCOMPATIBLE_COMMANDER_PAIR, "The two commanders do not have compatible partner abilities", commanders);
    }

    private static boolean backgroundPair(CardDetailsResponse chooser, CardDetailsResponse other) {
        return canLead(chooser) && ability(chooser, "choose a background") && background(other);
    }

    private static boolean friends(CardDetailsResponse card) {
        return ability(card, "friends forever") || ability(card, "partner—friends forever");
    }

    private static boolean partnerWith(CardDetailsResponse card, CardDetailsResponse other) {
        return ability(card, "partner with " + normalize(characteristics(other).name()));
    }

    private static boolean ability(CardDetailsResponse card, String keyword) {
        // Match actual ability lines, not keywords quoted in other rules or reminder text.
        String text = normalize(characteristics(card).oracleText()).replaceAll("(?s)\\([^)]*\\)", "");
        return text.lines().anyMatch(line -> line.strip().replaceAll("[.]$", "").equals(keyword));
    }

    private static boolean doctor(CardDetailsResponse card) {
        String[] parts = normalize(characteristics(card).typeLine()).split("—", 2);
        return legendaryCreature(card) && parts.length == 2 && parts[1].strip().equals("time lord doctor");
    }

    private static RuleViolationException pairError(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode code,
                                                    String message, List<CardDetailsResponse> cards) {
        return new RuleViolationException(code, message, "commanderOracleIds",
                cards.stream().map(CardDetailsResponse::oracleId).toList());
    }
}

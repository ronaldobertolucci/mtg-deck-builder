package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardFaceResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation of a sole commander using the English Oracle data supplied by the catalog. */
public final class CommanderEligibilityRules {
    private static final Set<String> FRONT_FACE_LAYOUTS = Set.of("modal_dfc", "transform", "flip", "adventure");
    private CommanderEligibilityRules() {}

    public static void validate(CardDetailsResponse card) {
        if (card == null) throw incomplete(null);
        // Ban and format legality always take precedence over type and textual exceptions.
        CardLegalityRules.enforce(card, Format.COMMANDER, 1, "commanderOracleIds");
        if (!canLead(card) || background(card)) {
            throw rejection(RuleErrorCode.COMMANDER_NOT_ELIGIBLE,
                    "Card is not eligible to be the sole commander", card);
        }
    }

    static CardFaceResponse characteristics(CardDetailsResponse card) {
        if (card == null) throw incomplete(null);
        String layout = normalize(card.layout());
        if (FRONT_FACE_LAYOUTS.contains(layout)) {
            if (card.cardFaces().size() < 2 || card.cardFaces().getFirst() == null) throw incomplete(card);
            CardFaceResponse front = card.cardFaces().getFirst();
            if (front.name() == null || front.name().isBlank() || front.typeLine() == null
                    || front.typeLine().isBlank() || front.oracleText() == null) throw incomplete(card);
            return front;
        }
        // Multiface layouts have distinct rules; do not silently combine their characteristics.
        if (!card.cardFaces().isEmpty()) throw incomplete(card);
        if (card.typeLine() == null || card.typeLine().isBlank()) throw incomplete(card);
        return new CardFaceResponse(card.name(), card.typeLine(), card.oracleText());
    }

    static boolean canLead(CardDetailsResponse card) {
        CardFaceResponse face = characteristics(card);
        if (legendaryCreature(card)) return true;
        if (face.name() == null || face.name().isBlank()) throw incomplete(card);
        String permission = normalize(face.name()) + " can be your commander";
        return normalize(face.oracleText()).lines()
                .anyMatch(line -> line.strip().replaceAll("[.]$", "").equals(permission));
    }

    static boolean legendaryCreature(CardDetailsResponse card) {
        return type(card, "legendary") && type(card, "creature");
    }

    static boolean background(CardDetailsResponse card) {
        return type(card, "legendary") && type(card, "enchantment") && subtype(card, "background");
    }

    static boolean type(CardDetailsResponse card, String word) {
        String types = normalize(characteristics(card).typeLine()).split("—", 2)[0];
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(types).find();
    }

    private static boolean subtype(CardDetailsResponse card, String word) {
        String[] parts = normalize(characteristics(card).typeLine()).split("—", 2);
        return parts.length == 2 && Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(parts[1]).find();
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[ \t]*[—–][ \t]*", "—");
    }

    static RuleViolationException rejection(RuleErrorCode code, String message, CardDetailsResponse card) {
        return new RuleViolationException(code, message, "commanderOracleIds",
                card == null || card.oracleId() == null ? List.of() : List.of(card.oracleId()));
    }

    private static RuleViolationException incomplete(CardDetailsResponse card) {
        return rejection(RuleErrorCode.COMMANDER_DATA_INCOMPLETE,
                "Commander characteristics cannot be confirmed from the available card data", card);
    }
}

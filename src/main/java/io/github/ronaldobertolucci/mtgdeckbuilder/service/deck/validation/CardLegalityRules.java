package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import java.util.Locale;
import java.util.List;
import static io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.*;

final class CardLegalityRules {
    private CardLegalityRules() {}

    static int enforce(CardDetailsResponse card, Format format, int copyLimit) {
        return enforce(card, format, copyLimit, null);
    }

    static int enforce(CardDetailsResponse card, Format format, int copyLimit, String field) {
        CardLegality legality = card.legalities().getOrDefault(format.name().toLowerCase(Locale.ROOT), CardLegality.UNKNOWN);
        return switch (legality) {
            case LEGAL -> copyLimit;
            case RESTRICTED -> 1;
            case NOT_LEGAL, BANNED, UNKNOWN -> throw new RuleViolationException(
                    legality == CardLegality.BANNED ? CARD_BANNED
                            : legality == CardLegality.NOT_LEGAL ? CARD_NOT_LEGAL : CARD_LEGALITY_UNKNOWN,
                    "Card " + card.name() + " cannot be added in " + format + ": " + legality,
                    field, card.oracleId() == null ? List.of() : List.of(card.oracleId()));
        };
    }
}

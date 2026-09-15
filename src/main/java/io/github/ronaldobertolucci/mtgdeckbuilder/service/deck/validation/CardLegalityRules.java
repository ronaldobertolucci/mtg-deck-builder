package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import java.util.Locale;

final class CardLegalityRules {
    private CardLegalityRules() {}

    static int enforce(CardDetailsResponse card, Format format, int copyLimit) {
        CardLegality legality = card.legalities().getOrDefault(format.name().toLowerCase(Locale.ROOT), CardLegality.UNKNOWN);
        return switch (legality) {
            case LEGAL -> copyLimit;
            case RESTRICTED -> 1;
            case NOT_LEGAL, BANNED, UNKNOWN -> throw new RuleViolationException(
                    "Card " + card.name() + " cannot be added in " + format + ": " + legality);
        };
    }
}

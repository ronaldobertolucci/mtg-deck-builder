package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum CardLegality {
    NOT_LEGAL, BANNED, RESTRICTED, LEGAL, UNKNOWN;

    @JsonCreator
    public static CardLegality fromValue(String value) {
        if (value == null) return UNKNOWN;
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return UNKNOWN; }
    }
}

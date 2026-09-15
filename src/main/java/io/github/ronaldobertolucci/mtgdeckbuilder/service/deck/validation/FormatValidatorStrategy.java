package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;

public interface FormatValidatorStrategy {
    boolean supports(Format format);

    /** Validates an additional quantity before mutation; newCard must not already be in deck. */
    void validateCardAddition(Deck deck, DeckCard newCard, CardDetailsResponse cardDetails);
}

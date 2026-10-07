package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import jakarta.validation.constraints.*;

public record ImportDeckRequest(@NotBlank(message = "Deck name is required") @Size(max = 255, message = "Deck name must not exceed 255 characters") String name,
                                @NotNull(message = "Format is required") Format format,
                                @NotBlank(message = "Deck import text is required") String rawText) {}

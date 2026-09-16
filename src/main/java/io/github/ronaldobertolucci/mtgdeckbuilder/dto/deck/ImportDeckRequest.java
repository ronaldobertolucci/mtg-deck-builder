package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import jakarta.validation.constraints.*;

public record ImportDeckRequest(@NotBlank @Size(max = 255) String name,
                                @NotNull Format format,
                                @NotBlank String rawText) {}

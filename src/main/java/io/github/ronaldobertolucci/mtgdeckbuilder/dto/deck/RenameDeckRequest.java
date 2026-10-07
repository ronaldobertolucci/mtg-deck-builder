package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDeckRequest(@NotBlank(message = "Deck name is required") @Size(max = 255, message = "Deck name must not exceed 255 characters") String name) {}

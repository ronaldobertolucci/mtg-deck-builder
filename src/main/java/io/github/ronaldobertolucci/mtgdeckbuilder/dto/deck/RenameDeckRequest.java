package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDeckRequest(@NotBlank @Size(max = 255) String name) {}

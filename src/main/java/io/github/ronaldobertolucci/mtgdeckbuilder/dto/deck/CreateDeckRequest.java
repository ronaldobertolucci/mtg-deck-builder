package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import io.github.ronaldobertolucci.mtgdeckbuilder.validation.ValidCommander;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

@ValidCommander
public record CreateDeckRequest(@NotBlank(message = "Deck name is required") @Size(max = 255, message = "Deck name must not exceed 255 characters") String name,
                                @NotNull(message = "Format is required") Format format,
                                @Size(max = 2, message = "At most two commander oracle IDs are allowed")
                                List<@NotNull(message = "Commander oracle ID is required") UUID> commanderOracleIds) {}

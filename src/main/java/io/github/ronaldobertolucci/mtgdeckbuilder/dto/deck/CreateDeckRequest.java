package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import io.github.ronaldobertolucci.mtgdeckbuilder.validation.ValidCommander;
import jakarta.validation.constraints.*;
import java.util.UUID;
@ValidCommander
public record CreateDeckRequest(@NotBlank @Size(max = 255) String name,
                                @NotNull Format format, UUID commanderOracleId) {}

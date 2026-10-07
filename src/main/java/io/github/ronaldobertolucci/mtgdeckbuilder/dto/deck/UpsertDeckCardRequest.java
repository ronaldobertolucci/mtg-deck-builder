package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record UpsertDeckCardRequest(@NotNull(message = "Oracle ID is required") UUID oracleId, @NotNull(message = "Board type is required") BoardType boardType,
                                    @NotNull(message = "Quantity is required")
                                    @PositiveOrZero(message = "Quantity must be zero or greater") Integer quantity) {}

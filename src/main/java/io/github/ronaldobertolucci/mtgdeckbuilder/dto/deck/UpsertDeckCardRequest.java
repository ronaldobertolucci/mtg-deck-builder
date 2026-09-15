package io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record UpsertDeckCardRequest(@NotNull UUID oracleId, @NotNull BoardType boardType,
                                    @NotNull @PositiveOrZero Integer quantity) {}

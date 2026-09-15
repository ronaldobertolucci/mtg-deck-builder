package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

import java.util.UUID;

public class CardNotFoundException extends RuntimeException {
    public CardNotFoundException(UUID oracleId, Throwable cause) {
        super("Card not found: " + oracleId, cause);
    }
}

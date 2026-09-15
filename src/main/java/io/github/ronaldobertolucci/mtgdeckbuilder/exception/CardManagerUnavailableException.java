package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

public class CardManagerUnavailableException extends RuntimeException {
    public CardManagerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

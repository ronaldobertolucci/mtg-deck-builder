package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
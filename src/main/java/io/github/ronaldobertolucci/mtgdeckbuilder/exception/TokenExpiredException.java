package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String message) {
        super(message);
    }
}
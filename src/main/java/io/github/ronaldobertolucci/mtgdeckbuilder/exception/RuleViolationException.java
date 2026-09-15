package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

public class RuleViolationException extends RuntimeException {
    public RuleViolationException(String message) {
        super(message);
    }
}

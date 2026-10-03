package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

public class RefreshAuthenticationException extends RuntimeException {
    public RefreshAuthenticationException() { super("Session expired or revoked. Please log in again."); }
}

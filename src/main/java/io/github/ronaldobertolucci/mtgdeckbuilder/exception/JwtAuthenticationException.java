package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

import org.springframework.security.authentication.BadCredentialsException;

public class JwtAuthenticationException extends BadCredentialsException {
    private final String code;

    public JwtAuthenticationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

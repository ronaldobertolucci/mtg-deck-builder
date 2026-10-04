package io.github.ronaldobertolucci.mtgdeckbuilder.exception;

import org.springframework.security.authentication.AccountStatusException;

public class EmailNotVerifiedException extends AccountStatusException {
    public EmailNotVerifiedException() {
        super("Email address has not been verified");
    }
}

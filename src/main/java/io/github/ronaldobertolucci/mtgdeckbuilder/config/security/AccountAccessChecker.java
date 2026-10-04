package io.github.ronaldobertolucci.mtgdeckbuilder.config.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.EmailNotVerifiedException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;

/** Runs only after password verification, before successful authentication. */
final class AccountAccessChecker implements UserDetailsChecker {
    private final AccountStatusUserDetailsChecker statusChecker = new AccountStatusUserDetailsChecker();

    @Override
    public void check(UserDetails details) {
        if (details instanceof User user) {
            // Administrative restriction takes precedence even if email is also unconfirmed.
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                throw new DisabledException("Account is disabled");
            }
            if (!user.isEmailVerified()) {
                throw new EmailNotVerifiedException();
            }
        }
        statusChecker.check(details);
    }
}

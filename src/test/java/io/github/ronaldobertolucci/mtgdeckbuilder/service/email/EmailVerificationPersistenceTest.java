package io.github.ronaldobertolucci.mtgdeckbuilder.service.email;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.EmailVerificationToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.EmailVerificationTokenRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@ActiveProfiles("test")
@Import(EmailVerificationService.class)
class EmailVerificationPersistenceTest {
    @Autowired UserRepository users;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired EmailVerificationService service;
    @Autowired TestEntityManager entities;
    @MockitoBean EmailService email;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmationPersistsWithoutChangingAdministrativeState(boolean enabled) {
        User user = users.save(User.builder().firstName("Test").lastName("User")
                .email("person@example.com").password("hash").dateOfBirth(LocalDate.of(1990, 1, 1))
                .enabled(enabled).emailVerified(false).build());
        tokens.save(EmailVerificationToken.builder().token("confirmation-token").user(user)
                .expiryDate(LocalDateTime.now().plusDays(1)).build());
        entities.flush();
        entities.clear();

        service.verifyEmail("confirmation-token");
        entities.flush();
        entities.clear();

        User persisted = users.findById(user.getId()).orElseThrow();
        assertEquals(enabled, persisted.getEnabled());
        assertTrue(persisted.isEmailVerified());
        assertEquals(enabled, persisted.isEnabled());
        assertTrue(tokens.findByToken("confirmation-token").orElseThrow().isUsed());
        verifyNoInteractions(email);
    }
}

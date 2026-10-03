package io.github.ronaldobertolucci.mtgdeckbuilder.service.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RefreshAuthenticationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "api.security.refresh.expiration-days=7"})
@Import(RefreshTokenService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenServiceTest {
    @Autowired RefreshTokenService service;
    @Autowired RefreshSessionRepository sessions;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @MockitoBean TokenService accessTokens;
    User user;

    @BeforeEach void setup() {
        tokens.deleteAll(); sessions.deleteAll(); users.deleteAll();
        user = users.save(User.builder().firstName("Test").lastName("User").email("refresh@example.com")
                .password("encoded").dateOfBirth(LocalDate.of(1990, 1, 1)).enabled(true).roles(new HashSet<>()).build());
        when(accessTokens.generateToken(any())).thenReturn("access-token");
    }

    @Test void rotatesAndStoresOnlyHashesWithFixedExpiry() {
        var first = service.create(user);
        var next = service.rotate(first.refreshToken());
        assertNotEquals(first.refreshToken(), next.refreshToken());
        assertEquals(first.expiresAt().getEpochSecond(), next.expiresAt().getEpochSecond());
        assertEquals(2, tokens.count());
        tokens.findAll().forEach(token -> {
            assertEquals(64, token.getHash().length());
            assertNotEquals(first.refreshToken(), token.getHash());
            assertNotEquals(next.refreshToken(), token.getHash());
        });
    }

    @Test void replayRevokesFamilyAndCommitsDespiteException() {
        var first = service.create(user);
        var next = service.rotate(first.refreshToken());
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(first.refreshToken()));
        assertTrue(sessions.findAll().getFirst().isRevoked());
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(next.refreshToken()));
    }

    @Test void logoutUsingOldTokenRevokesCurrentSessionOnly() {
        var first = service.create(user);
        var next = service.rotate(first.refreshToken());
        var other = service.create(user);
        service.revoke(first.refreshToken());
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(next.refreshToken()));
        assertNotNull(service.rotate(other.refreshToken()));
        service.revoke(null);
        service.revoke("invalid");
    }

    @Test void revokeAllBlocksEverySession() {
        var a = service.create(user); var b = service.create(user);
        service.revokeAll(user.getId());
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(a.refreshToken()));
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(b.refreshToken()));
    }

    @Test void expiredSessionCannotRenew() {
        var grant = service.create(user);
        var session = sessions.findAll().getFirst();
        session.setExpiresAt(Instant.now().minusSeconds(1)); sessions.save(session);
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(grant.refreshToken()));
    }

    @Test void cleanupRemovesExpiredHistoryButKeepsLiveSessions() {
        var expired = service.create(user);
        var row = sessions.findAll().getFirst();
        row.setExpiresAt(Instant.now().minusSeconds(1)); sessions.save(row);
        var live = service.create(user);
        service.purgeExpired();
        assertEquals(1, sessions.count());
        assertEquals(1, tokens.count());
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(expired.refreshToken()));
        assertNotNull(service.rotate(live.refreshToken()));
    }

    @Test void disabledAccountCannotRenew() {
        var grant = service.create(user);
        user.setEnabled(false); users.save(user);
        assertThrows(RefreshAuthenticationException.class, () -> service.rotate(grant.refreshToken()));
    }

    @Test void rejectsMissingMalformedAndUnknownTokens() {
        for (String token : Arrays.asList(null, "", "invalid", "a".repeat(43))) {
            assertThrows(RefreshAuthenticationException.class, () -> service.rotate(token));
        }
    }

    @Test void concurrentRefreshSerializesAndDetectsReplay() throws Exception {
        var grant = service.create(user);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> rotate = () -> {
                start.await();
                try { service.rotate(grant.refreshToken()); return true; }
                catch (RefreshAuthenticationException ex) { return false; }
            };
            var a = executor.submit(rotate); var b = executor.submit(rotate);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertTrue(sessions.findAll().getFirst().isRevoked());
        }
    }
}

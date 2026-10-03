package io.github.ronaldobertolucci.mtgdeckbuilder.service.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.TokenDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RefreshAuthenticationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshSessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final TokenService accessTokens;
    private static final SecureRandom RANDOM = new SecureRandom();
    @Value("${api.security.refresh.expiration-days:7}") private int expirationDays;
    @Value("${api.security.token.expiration-hours:2}") private int accessHours;

    public record Grant(TokenDto access, String refreshToken, Instant expiresAt) {}

    @Transactional
    public Grant create(User user) {
        var session = new RefreshSession();
        session.setId(UUID.randomUUID());
        session.setUser(user);
        session.setExpiresAt(Instant.now().plus(Duration.ofDays(expirationDays)));
        return issue(session);
    }

    // Replay revocation must commit even though the request is rejected.
    @Transactional(noRollbackFor = RefreshAuthenticationException.class)
    public Grant rotate(String raw) {
        var token = lookup(raw).orElseThrow(RefreshAuthenticationException::new);
        var session = sessions.lockById(token.getSessionId()).orElseThrow(RefreshAuthenticationException::new);
        if (session.isRevoked() || !session.getExpiresAt().isAfter(Instant.now())
                || !session.getCurrentHash().equals(token.getHash()) || !session.getUser().isEnabled()) {
            session.setRevoked(true);
            throw new RefreshAuthenticationException();
        }
        return issue(session);
    }

    @Transactional
    public void revoke(String raw) {
        lookup(raw).flatMap(token -> sessions.lockById(token.getSessionId()))
                .ifPresent(session -> session.setRevoked(true));
    }

    @Transactional
    public void revokeAll(Long userId) { sessions.revokeByUserId(userId); }

    @Scheduled(cron = "${api.security.refresh.cleanup-cron:0 30 2 * * ?}")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        tokens.deleteExpired(now);
        sessions.deleteExpired(now);
    }

    private Optional<RefreshToken> lookup(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return tokens.findById(hash(raw));
    }

    private Grant issue(RefreshSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(raw);
        session.setCurrentHash(hash);
        sessions.save(session);
        tokens.save(new RefreshToken(hash, session.getId()));
        User user = session.getUser();
        return new Grant(new TokenDto(accessTokens.generateToken(user), accessHours * 3600L, new UserDto(user)),
                raw, session.getExpiresAt());
    }

    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

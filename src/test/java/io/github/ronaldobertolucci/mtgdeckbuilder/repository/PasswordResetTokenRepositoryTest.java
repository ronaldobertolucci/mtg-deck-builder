package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.PasswordResetToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PasswordResetTokenRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER role not found"));

        testUser = User.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("password")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .enabled(true)
                .roles(new HashSet<>(Set.of(userRole)))
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    void findByToken_WhenTokenExists_ShouldReturnToken() {
        // Arrange
        PasswordResetToken token = new PasswordResetToken("test-token-123", testUser, 24);
        tokenRepository.save(token);

        // Act
        Optional<PasswordResetToken> result = tokenRepository.findByToken("test-token-123");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("test-token-123", result.get().getToken());
    }

    @Test
    void findByToken_WhenTokenDoesNotExist_ShouldReturnEmpty() {
        // Act
        Optional<PasswordResetToken> result = tokenRepository.findByToken("nonexistent");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByUserIdAndUsedFalse_WhenValidTokenExists_ShouldReturnToken() {
        // Arrange
        PasswordResetToken token = new PasswordResetToken("test-token-123", testUser, 24);
        token.setUsed(false);
        tokenRepository.save(token);

        // Act
        Optional<PasswordResetToken> result = tokenRepository.findByUserIdAndUsedFalse(testUser.getId());

        // Assert
        assertTrue(result.isPresent());
        assertFalse(result.get().getUsed());
    }

    @Test
    void findByUserIdAndUsedFalse_WhenTokenIsUsed_ShouldReturnEmpty() {
        // Arrange
        PasswordResetToken token = new PasswordResetToken("test-token-123", testUser, 24);
        token.setUsed(true);
        tokenRepository.save(token);

        // Act
        Optional<PasswordResetToken> result = tokenRepository.findByUserIdAndUsedFalse(testUser.getId());

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByUserId_WhenUserHasTokens_ShouldReturnList() {
        // Arrange
        tokenRepository.save(new PasswordResetToken("token-1", testUser, 24));
        tokenRepository.save(new PasswordResetToken("token-2", testUser, 24));

        // Act
        List<PasswordResetToken> tokens = tokenRepository.findByUserId(testUser.getId());

        // Assert
        assertEquals(2, tokens.size());
    }

    @Test
    void existsByToken_WhenTokenExists_ShouldReturnTrue() {
        // Arrange
        tokenRepository.save(new PasswordResetToken("test-token-123", testUser, 24));

        // Act
        boolean exists = tokenRepository.existsByToken("test-token-123");

        // Assert
        assertTrue(exists);
    }

    @Test
    void existsByToken_WhenTokenDoesNotExist_ShouldReturnFalse() {
        // Act
        boolean exists = tokenRepository.existsByToken("nonexistent");

        // Assert
        assertFalse(exists);
    }

    @Test
    void deleteExpiredTokens_WhenExpiredTokensExist_ShouldDeleteThem() {
        // Arrange
        PasswordResetToken expiredToken = new PasswordResetToken();
        expiredToken.setToken("expired-token");
        expiredToken.setUser(testUser);
        expiredToken.setExpiryDate(LocalDateTime.now().minusHours(1));
        expiredToken.setUsed(false);
        tokenRepository.save(expiredToken);

        PasswordResetToken validToken = new PasswordResetToken("valid-token", testUser, 24);
        tokenRepository.save(validToken);

        entityManager.flush();

        // Act
        tokenRepository.deleteExpiredTokens(LocalDateTime.now());
        entityManager.flush();

        // Assert
        Optional<PasswordResetToken> expiredResult = tokenRepository.findByToken("expired-token");
        Optional<PasswordResetToken> validResult = tokenRepository.findByToken("valid-token");

        assertTrue(expiredResult.isEmpty());
        assertTrue(validResult.isPresent());
    }

    @Test
    void deleteByUserId_WhenUserHasTokens_ShouldDeleteAllTokens() {
        // Arrange
        tokenRepository.save(new PasswordResetToken("token-1", testUser, 24));
        tokenRepository.save(new PasswordResetToken("token-2", testUser, 24));
        entityManager.flush();

        // Act
        tokenRepository.deleteByUserId(testUser.getId());
        entityManager.flush();

        // Assert
        List<PasswordResetToken> tokens = tokenRepository.findByUserId(testUser.getId());
        assertTrue(tokens.isEmpty());
    }
}
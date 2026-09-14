package io.github.ronaldobertolucci.mtgdeckbuilder.service.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @InjectMocks
    private TokenService tokenService;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role userRole = new Role(1L, "USER");

        testUser = User.builder()
                .id(1L)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .enabled(true)
                .roles(new HashSet<>(Set.of(userRole)))
                .build();

        // Configurar @Value fields usando reflection
        ReflectionTestUtils.setField(tokenService, "secret", "test-secret-key-for-jwt-token-generation-minimum-256-bits");
        ReflectionTestUtils.setField(tokenService, "issuer", "TestApp");
        ReflectionTestUtils.setField(tokenService, "expirationHours", 2);
    }

    @Test
    void generateToken_WhenUserIsValid_ShouldReturnValidToken() {
        // Act
        String token = tokenService.generateToken(testUser);

        // Assert
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3); // JWT tem 3 partes separadas por "."
    }

    @Test
    void getSubject_WhenTokenIsValid_ShouldReturnEmail() {
        // Arrange
        String token = tokenService.generateToken(testUser);

        // Act
        String subject = tokenService.getSubject(token);

        // Assert
        assertEquals("john@example.com", subject);
    }

    @Test
    void getSubject_WhenTokenIsInvalid_ShouldThrowException() {
        // Arrange
        String invalidToken = "invalid.token.here";

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> tokenService.getSubject(invalidToken));

        assertTrue(exception.getMessage().contains("JWT") ||
                exception.getMessage().contains("token") ||
                exception.getMessage().contains("Invalid"));
    }

    @Test
    void generateToken_WhenUserHasDifferentId_ShouldIncludeCorrectId() {
        // Arrange
        User anotherUser = User.builder()
                .id(999L)
                .firstName("Jane")
                .lastName("Smith")
                .email("jane@example.com")
                .password("password")
                .dateOfBirth(LocalDate.of(1995, 5, 5))
                .enabled(true)
                .roles(new HashSet<>(Set.of(new Role(1L, "USER"))))
                .build();

        // Act
        String token1 = tokenService.generateToken(testUser);
        String token2 = tokenService.generateToken(anotherUser);

        // Assert
        String subject1 = tokenService.getSubject(token1);
        String subject2 = tokenService.getSubject(token2);

        assertEquals("john@example.com", subject1);
        assertEquals("jane@example.com", subject2);
        assertNotEquals(token1, token2); // Tokens de usuários diferentes devem ser diferentes
    }

    @Test
    void generateToken_ShouldCreateTokenWithCorrectStructure() {
        // Act
        String token = tokenService.generateToken(testUser);

        // Assert
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT deve ter 3 partes: header.payload.signature");

        // Verifica que cada parte tem conteúdo
        assertTrue(parts[0].length() > 0, "Header não deve estar vazio");
        assertTrue(parts[1].length() > 0, "Payload não deve estar vazio");
        assertTrue(parts[2].length() > 0, "Signature não deve estar vazia");
    }

    @Test
    void getSubject_WhenTokenHasWrongSignature_ShouldThrowException() {
        // Arrange
        String token = tokenService.generateToken(testUser);

        // Modifica a assinatura do token (última parte)
        String[] parts = token.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + ".tamperedSignature";

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> tokenService.getSubject(tamperedToken));

        assertTrue(exception.getMessage().contains("JWT") ||
                exception.getMessage().contains("token") ||
                exception.getMessage().contains("Invalid"));
    }

    @Test
    void generateToken_WithDifferentIssuer_ShouldNotValidateWithDifferentIssuer() {
        // Arrange
        String token = tokenService.generateToken(testUser);

        // Muda o issuer para outro valor
        ReflectionTestUtils.setField(tokenService, "issuer", "DifferentIssuer");

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> tokenService.getSubject(token));

        assertTrue(exception.getMessage().contains("JWT") ||
                exception.getMessage().contains("token") ||
                exception.getMessage().contains("Invalid"));
    }
}
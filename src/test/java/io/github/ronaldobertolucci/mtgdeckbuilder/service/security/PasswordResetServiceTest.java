package io.github.ronaldobertolucci.mtgdeckbuilder.service.security;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.InvalidTokenException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.PasswordResetToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.PasswordResetTokenRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailService;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.TokenExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordResetService passwordResetService;

    private User testUser;
    private PasswordResetToken validToken;
    private PasswordResetToken expiredToken;

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

        validToken = new PasswordResetToken("valid-token-123", testUser, 24);

        expiredToken = new PasswordResetToken();
        expiredToken.setId(2L);
        expiredToken.setToken("expired-token-123");
        expiredToken.setUser(testUser);
        expiredToken.setExpiryDate(LocalDateTime.now().minusHours(1));
        expiredToken.setUsed(false);

        // Set @Value fields using reflection
        ReflectionTestUtils.setField(passwordResetService, "frontendUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(passwordResetService, "appName", "TestApp");
        ReflectionTestUtils.setField(passwordResetService, "tokenExpiryHours", 24);
    }

    @Test
    void createPasswordResetToken_WhenUserExists_ShouldCreateTokenAndSendEmail() {
        // Arrange
        when(userRepository.findByEmailWithRoles(anyString())).thenReturn(Optional.of(testUser));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenReturn(validToken);
        doNothing().when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        // Act
        passwordResetService.createPasswordResetToken("john@example.com");

        // Assert
        verify(userRepository, times(1)).findByEmailWithRoles("john@example.com");
        verify(tokenRepository, times(1)).deleteByUserId(1L);

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, times(1)).save(tokenCaptor.capture());

        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertNotNull(savedToken.getToken());
        assertEquals(testUser, savedToken.getUser());
        assertFalse(savedToken.isExpired());

        verify(emailService, times(1)).sendHtmlEmail(
                eq("john@example.com"),
                anyString(),
                anyString()
        );
    }

    @Test
    void createPasswordResetToken_WhenUserDoesNotExist_ShouldNotCreateToken() {
        // Arrange
        when(userRepository.findByEmailWithRoles(anyString())).thenReturn(Optional.empty());

        // Act
        passwordResetService.createPasswordResetToken("nonexistent@example.com");

        // Assert
        verify(userRepository, times(1)).findByEmailWithRoles("nonexistent@example.com");
        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void validatePasswordResetToken_WhenTokenIsValid_ShouldNotThrowException() {
        // Arrange
        when(tokenRepository.findByToken("valid-token-123")).thenReturn(Optional.of(validToken));

        // Act & Assert
        assertDoesNotThrow(() -> passwordResetService.validatePasswordResetToken("valid-token-123"));

        verify(tokenRepository, times(1)).findByToken("valid-token-123");
    }

    @Test
    void validatePasswordResetToken_WhenTokenDoesNotExist_ShouldThrowException() {
        // Arrange
        when(tokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(InvalidTokenException.class,
                () -> passwordResetService.validatePasswordResetToken("invalid-token"));

        verify(tokenRepository, times(1)).findByToken("invalid-token");
    }

    @Test
    void validatePasswordResetToken_WhenTokenIsExpired_ShouldThrowException() {
        // Arrange
        when(tokenRepository.findByToken("expired-token-123")).thenReturn(Optional.of(expiredToken));

        // Act & Assert
        assertThrows(TokenExpiredException.class,
                () -> passwordResetService.validatePasswordResetToken("expired-token-123"));
    }

    @Test
    void validatePasswordResetToken_WhenTokenIsUsed_ShouldThrowException() {
        // Arrange
        validToken.setUsed(true);
        when(tokenRepository.findByToken("valid-token-123")).thenReturn(Optional.of(validToken));

        // Act & Assert
        assertThrows(TokenExpiredException.class,
                () -> passwordResetService.validatePasswordResetToken("valid-token-123"));
    }

    @Test
    void resetPassword_WhenTokenIsValid_ShouldResetPasswordAndMarkTokenUsed() {
        // Arrange
        when(tokenRepository.findByToken("valid-token-123")).thenReturn(Optional.of(validToken));
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(tokenRepository.save(any(PasswordResetToken.class))).thenReturn(validToken);

        // Act
        passwordResetService.resetPassword("valid-token-123", "newPassword123");

        // Assert
        verify(passwordEncoder, times(1)).encode("newPassword123");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        assertEquals("newEncodedPassword", userCaptor.getValue().getPassword());

        assertTrue(validToken.getUsed());
        verify(tokenRepository, times(1)).save(validToken);
    }

    @Test
    void resetPassword_WhenTokenIsInvalid_ShouldThrowException() {
        // Arrange
        when(tokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(InvalidTokenException.class,
                () -> passwordResetService.resetPassword("invalid-token", "newPassword"));

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPassword_WhenTokenIsExpired_ShouldThrowException() {
        // Arrange
        when(tokenRepository.findByToken("expired-token-123")).thenReturn(Optional.of(expiredToken));

        // Act & Assert
        assertThrows(TokenExpiredException.class,
                () -> passwordResetService.resetPassword("expired-token-123", "newPassword"));

        verify(userRepository, never()).save(any(User.class));
    }
}
package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.PasswordResetTokenResetDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.GlobalExceptionHandler;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.PasswordResetToken;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.PasswordResetTokenRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.PasswordResetService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetValidationTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private User user;
    private PasswordResetToken token;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).password("original-password-hash").build();
        token = new PasswordResetToken("valid-token", user, 24);
        PasswordResetService service = new PasswordResetService(
                tokenRepository, userRepository, emailService, passwordEncoder, refreshTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"        ", "1234567"})
    void invalidPassword_ShouldPreservePasswordAndLeaveTokenAvailable(String password) throws Exception {
        mockMvc.perform(post("/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetTokenResetDto(token.getToken(), password))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("newPassword")));

        assertEquals("original-password-hash", user.getPassword());
        assertFalse(token.getUsed());
        assertTrue(token.isValid());
        verifyNoInteractions(tokenRepository, userRepository, passwordEncoder, refreshTokenService, emailService);

        // The same token can still complete a reset after the rejected request.
        resetSuccessfully("12345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", " 123456 "})
    void eightCharacterPassword_ShouldBeAcceptedWithoutModification(String password) throws Exception {
        resetSuccessfully(password);
    }

    private void resetSuccessfully(String password) throws Exception {
        when(tokenRepository.findByToken(token.getToken())).thenReturn(Optional.of(token));
        when(passwordEncoder.encode(password)).thenReturn("new-password-hash");

        mockMvc.perform(post("/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetTokenResetDto(token.getToken(), password))))
                .andExpect(status().isOk());

        verify(passwordEncoder).encode(password);
        verify(userRepository).save(user);
        verify(tokenRepository).save(token);
        verify(refreshTokenService).revokeAll(user.getId());
        assertEquals("new-password-hash", user.getPassword());
        assertTrue(token.getUsed());
    }
}

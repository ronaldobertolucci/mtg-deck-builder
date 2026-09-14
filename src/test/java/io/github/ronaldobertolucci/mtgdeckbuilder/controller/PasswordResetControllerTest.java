package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.TestConfig;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.InvalidTokenException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.TokenExpiredException;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.PasswordResetTokenForgotDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.PasswordResetTokenResetDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.PasswordResetService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PasswordResetController.class)
@Import({TestConfig.class, SecurityConfigurations.class})
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @Test
    void forgotPassword_WhenEmailIsValid_ShouldReturnSuccess() throws Exception {
        // Arrange
        PasswordResetTokenForgotDto dto = new PasswordResetTokenForgotDto("john@example.com");
        doNothing().when(passwordResetService).createPasswordResetToken(anyString());

        // Act & Assert
        mockMvc.perform(post("/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        verify(passwordResetService, times(1)).createPasswordResetToken("john@example.com");
    }

    @Test
    void forgotPassword_WhenEmailIsBlank_ShouldReturnBadRequest() throws Exception {
        // Arrange
        PasswordResetTokenForgotDto dto = new PasswordResetTokenForgotDto("");

        // Act & Assert
        mockMvc.perform(post("/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).createPasswordResetToken(anyString());
    }

    @Test
    void forgotPassword_WhenEmailIsInvalid_ShouldReturnBadRequest() throws Exception {
        // Arrange
        PasswordResetTokenForgotDto dto = new PasswordResetTokenForgotDto("invalid-email");

        // Act & Assert
        mockMvc.perform(post("/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).createPasswordResetToken(anyString());
    }

    @Test
    void validateToken_WhenTokenIsValid_ShouldReturnSuccess() throws Exception {
        // Arrange
        doNothing().when(passwordResetService).validatePasswordResetToken(anyString());

        // Act & Assert
        mockMvc.perform(get("/password/reset/validate")
                        .param("token", "valid-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Token is valid"));

        verify(passwordResetService, times(1)).validatePasswordResetToken("valid-token-123");
    }

    @Test
    void validateToken_WhenTokenIsInvalid_ShouldReturnBadRequest() throws Exception {
        // Arrange
        doThrow(new InvalidTokenException("Invalid token"))
                .when(passwordResetService).validatePasswordResetToken(anyString());

        // Act & Assert
        mockMvc.perform(get("/password/reset/validate")
                        .param("token", "invalid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid token"));

        verify(passwordResetService, times(1)).validatePasswordResetToken("invalid-token");
    }

    @Test
    void validateToken_WhenTokenIsExpired_ShouldReturnBadRequest() throws Exception {
        // Arrange
        doThrow(new TokenExpiredException("Token expired"))
                .when(passwordResetService).validatePasswordResetToken(anyString());

        // Act & Assert
        mockMvc.perform(get("/password/reset/validate")
                        .param("token", "expired-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token expired"));

        verify(passwordResetService, times(1)).validatePasswordResetToken("expired-token");
    }

    @Test
    void resetPassword_WhenTokenAndPasswordAreValid_ShouldReturnSuccess() throws Exception {
        // Arrange
        PasswordResetTokenResetDto dto = new PasswordResetTokenResetDto(
                "valid-token-123",
                "newPassword123"
        );
        doNothing().when(passwordResetService).resetPassword(anyString(), anyString());

        // Act & Assert
        mockMvc.perform(post("/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password has been reset successfully"));

        verify(passwordResetService, times(1)).resetPassword("valid-token-123", "newPassword123");
    }

    @Test
    void resetPassword_WhenTokenIsBlank_ShouldReturnBadRequest() throws Exception {
        // Arrange
        PasswordResetTokenResetDto dto = new PasswordResetTokenResetDto("", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_WhenPasswordIsBlank_ShouldReturnBadRequest() throws Exception {
        // Arrange
        PasswordResetTokenResetDto dto = new PasswordResetTokenResetDto("valid-token", "");

        // Act & Assert
        mockMvc.perform(post("/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_WhenTokenIsInvalid_ShouldReturnBadRequest() throws Exception {
        // Arrange
        PasswordResetTokenResetDto dto = new PasswordResetTokenResetDto(
                "invalid-token",
                "newPassword123"
        );
        doThrow(new InvalidTokenException("Invalid token"))
                .when(passwordResetService).resetPassword(anyString(), anyString());

        // Act & Assert
        mockMvc.perform(post("/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid token"));

        verify(passwordResetService, times(1)).resetPassword("invalid-token", "newPassword123");
    }
}
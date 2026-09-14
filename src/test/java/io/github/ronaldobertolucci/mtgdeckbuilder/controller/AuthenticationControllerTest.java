package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.TestConfig;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.ResendVerificationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.LoginDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserRegistrationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailVerificationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.user.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthenticationController.class)
@Import({TestConfig.class, SecurityConfigurations.class})
class AuthenticationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    private User testUser;
    private UserDto userDto;

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

        userDto = new UserDto(testUser);
    }

    @Test
    void login_WhenCredentialsAreValid_ShouldReturnToken() throws Exception {
        // Arrange
        LoginDto loginDto = new LoginDto("john@example.com", "password123");

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(testUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(tokenService.generateToken(any(User.class))).thenReturn("mock-jwt-token");

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-jwt-token"))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("john@example.com"))
                .andExpect(jsonPath("$.user.firstName").value("John"));

        verify(authenticationManager, times(1)).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(tokenService, times(1)).generateToken(testUser);
    }

    @Test
    void login_WhenCredentialsAreInvalid_ShouldReturnUnauthorized() throws Exception {
        // Arrange
        LoginDto loginDto = new LoginDto("john@example.com", "wrongpassword");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isUnauthorized());

        verify(tokenService, never()).generateToken(any(User.class));
    }

    @Test
    void login_WhenEmailIsBlank_ShouldReturnBadRequest() throws Exception {
        // Arrange
        LoginDto invalidDto = new LoginDto("", "password123");

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest());

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void login_WhenEmailIsInvalid_ShouldReturnBadRequest() throws Exception {
        // Arrange
        LoginDto invalidDto = new LoginDto("not-an-email", "password123");

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest());

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void register_WhenDataIsValid_ShouldCreateUser() throws Exception {
        // Arrange
        UserRegistrationDto registrationDto = new UserRegistrationDto(
                "John",
                "Doe",
                "john@example.com",
                LocalDate.of(1990, 1, 1),
                "password123"
        );

        when(userService.register(any(UserRegistrationDto.class))).thenReturn(userDto);

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("john@example.com"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.enabled").value(true));

        verify(userService, times(1)).register(any(UserRegistrationDto.class));
    }

    @Test
    void register_WhenEmailAlreadyExists_ShouldReturnBadRequest() throws Exception {
        // Arrange
        UserRegistrationDto registrationDto = new UserRegistrationDto(
                "John",
                "Doe",
                "john@example.com",
                LocalDate.of(1990, 1, 1),
                "password123"
        );

        when(userService.register(any(UserRegistrationDto.class)))
                .thenThrow(new IllegalArgumentException("Email already registered"));

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already registered"));

        verify(userService, times(1)).register(any(UserRegistrationDto.class));
    }

    @Test
    void register_WhenPasswordIsTooShort_ShouldReturnBadRequest() throws Exception {
        // Arrange
        UserRegistrationDto invalidDto = new UserRegistrationDto(
                "John",
                "Doe",
                "john@example.com",
                LocalDate.of(1990, 1, 1),
                "short"
        );

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).register(any());
    }

    @Test
    void register_WhenEmailIsInvalid_ShouldReturnBadRequest() throws Exception {
        // Arrange
        UserRegistrationDto invalidDto = new UserRegistrationDto(
                "John",
                "Doe",
                "invalid-email",
                LocalDate.of(1990, 1, 1),
                "password123"
        );

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).register(any());
    }

    @Test
    void register_WhenFirstNameIsBlank_ShouldReturnBadRequest() throws Exception {
        // Arrange
        UserRegistrationDto invalidDto = new UserRegistrationDto(
                "",
                "Doe",
                "john@example.com",
                LocalDate.of(1990, 1, 1),
                "password123"
        );

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).register(any());
    }

    // -------------------------------------------------------------------------
    // GET /auth/verify-email
    // -------------------------------------------------------------------------

    @Test
    void verifyEmail_WhenTokenIsValid_ShouldReturn200() throws Exception {
        doNothing().when(emailVerificationService).verifyEmail("valid-token");

        mockMvc.perform(get("/auth/verify-email")
                        .param("token", "valid-token"))
                .andExpect(status().isOk());

        verify(emailVerificationService, times(1)).verifyEmail("valid-token");
    }

    @Test
    void verifyEmail_WhenTokenIsInvalid_ShouldReturn400() throws Exception {
        doThrow(new IllegalArgumentException("Invalid verification token"))
                .when(emailVerificationService).verifyEmail("invalid-token");

        mockMvc.perform(get("/auth/verify-email")
                        .param("token", "invalid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyEmail_WhenTokenIsExpired_ShouldReturn400() throws Exception {
        doThrow(new IllegalStateException("Token has expired"))
                .when(emailVerificationService).verifyEmail("expired-token");

        mockMvc.perform(get("/auth/verify-email")
                        .param("token", "expired-token"))
                .andExpect(status().isConflict());
    }

    @Test
    void verifyEmail_WhenTokenAlreadyUsed_ShouldReturn400() throws Exception {
        doThrow(new IllegalStateException("Token has already been used"))
                .when(emailVerificationService).verifyEmail("used-token");

        mockMvc.perform(get("/auth/verify-email")
                        .param("token", "used-token"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /auth/resend-verification
    // -------------------------------------------------------------------------

    @Test
    void resendVerification_WhenEmailIsValid_ShouldReturn200() throws Exception {
        ResendVerificationDto dto = new ResendVerificationDto("john@example.com");
        doNothing().when(emailVerificationService).resendVerificationEmail("john@example.com");

        mockMvc.perform(post("/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(emailVerificationService, times(1)).resendVerificationEmail("john@example.com");
    }

    @Test
    void resendVerification_WhenEmailIsInvalid_ShouldReturn400() throws Exception {
        ResendVerificationDto dto = new ResendVerificationDto("not-an-email");

        mockMvc.perform(post("/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(emailVerificationService, never()).resendVerificationEmail(any());
    }

    @Test
    void resendVerification_WhenEmailNotFound_ShouldReturn404() throws Exception {
        ResendVerificationDto dto = new ResendVerificationDto("unknown@example.com");
        doThrow(new EntityNotFoundException("User not found"))
                .when(emailVerificationService).resendVerificationEmail("unknown@example.com");

        mockMvc.perform(post("/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendVerification_WhenAccountAlreadyVerified_ShouldReturn400() throws Exception {
        ResendVerificationDto dto = new ResendVerificationDto("john@example.com");
        doThrow(new IllegalStateException("Account is already verified"))
                .when(emailVerificationService).resendVerificationEmail("john@example.com");

        mockMvc.perform(post("/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }
}
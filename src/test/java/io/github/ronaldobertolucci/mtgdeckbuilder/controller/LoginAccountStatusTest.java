package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CorsConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.RefreshCookie;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.TokenDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailVerificationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.AuthenticationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.RefreshTokenService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real production AuthenticationManager, DaoAuthenticationProvider and BCrypt;
// mock only persistence and token/session issuance at the application boundary.
@WebMvcTest(controllers = AuthenticationController.class, properties = "cors.allowed-origins=http://localhost:4200")
@Import({SecurityConfigurations.class, CorsConfiguration.class, RefreshCookie.class, AuthenticationService.class})
class LoginAccountStatusTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean UserRepository users;
    @MockitoBean RefreshTokenService refreshTokens;
    @MockitoBean TokenService tokenService;
    @MockitoBean UserService userService;
    @MockitoBean EmailVerificationService verification;

    private User account;

    @BeforeEach
    void setUp() {
        account = User.builder().id(1L).email("person@example.com")
                .password(passwords.encode("correct-password")).enabled(false).build();
        when(users.findByEmailWithRoles(account.getEmail())).thenReturn(Optional.of(account));
    }

    @ParameterizedTest(name = "Correct password, state={0}")
    @ValueSource(strings = {"unconfirmed", "disabled", "disabled-unconfirmed"})
    void unavailableAccountReturnsStableErrorAfterPasswordValidation(String state) throws Exception {
        setState(state);
        boolean unconfirmed = state.equals("unconfirmed");
        login("correct-password")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.code").value(unconfirmed ? "EMAIL_NOT_VERIFIED" : "ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.message").value(unconfirmed ? "Email address has not been verified" : "Account is not enabled for sign-in"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist());
        verifyNoInteractions(refreshTokens, tokenService, verification);
    }

    @ParameterizedTest(name = "Wrong password, state={0}")
    @ValueSource(strings = {"unconfirmed", "disabled", "disabled-unconfirmed", "enabled", "unknown"})
    void invalidCredentialsNeverDiscloseAccountStatus(String state) throws Exception {
        setState(state);
        if (state.equals("unknown")) {
            when(users.findByEmailWithRoles(account.getEmail())).thenReturn(Optional.empty());
        }
        login("wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(refreshTokens, tokenService, verification);
    }

    @Test
    void validLoginPreservesTokenAndCookieContract() throws Exception {
        setState("enabled");
        when(refreshTokens.create(account)).thenReturn(new RefreshTokenService.Grant(
                new TokenDto("access-token", 7200L, new UserDto(account)),
                "refresh-secret", Instant.now().plusSeconds(600)));
        mvc.perform(post("/api/auth/login").contextPath("/api").header("X-CSRF-Protection", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("correct-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(7200))
                .andExpect(jsonPath("$.user.email").value(account.getEmail()))
                .andExpect(header().string("Set-Cookie", containsString("mtg_refresh=refresh-secret")))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(refreshTokens).create(account);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Database unavailable", "JWT storage unavailable"})
    void realRepositoryFailureRemainsInternalError(String message) throws Exception {
        when(users.findByEmailWithRoles(account.getEmail())).thenThrow(new RuntimeException(message));
        login("correct-password")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(refreshTokens, tokenService, verification);
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contextPath("/api").header("X-CSRF-Protection", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body(password)))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)))
                .andExpect(result -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }

    private void setState(String state) {
        account.setEnabled(!state.startsWith("disabled"));
        account.setEmailVerified(!state.contains("unconfirmed"));
    }

    private String body(String password) {
        return """
                {"email":"person@example.com","password":"%s"}
                """.formatted(password);
    }
}

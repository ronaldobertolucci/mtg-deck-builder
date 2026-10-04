package io.github.ronaldobertolucci.mtgdeckbuilder.config.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SessionExpirationTest.Probe.class, properties = {"api.security.token.secret=session-test-secret", "api.security.token.issuer=session-test"})
@Import({io.github.ronaldobertolucci.mtgdeckbuilder.service.security.AuthenticationService.class, SecurityConfigurations.class, SecurityFilter.class, TokenService.class, SessionExpirationTest.Probe.class})
class SessionExpirationTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;

    @RestController
    static class Probe {
        @GetMapping("/api/session-test") String protectedResource() { return "ok"; }
        @GetMapping("/auth/session-test") String publicResource() { return "public"; }
    }

    private String token(Instant expiration) {
        return JWT.create().withIssuer("session-test").withSubject("user@example.com")
                .withExpiresAt(expiration).sign(Algorithm.HMAC256("session-test-secret"));
    }

    @Test void missingTokenRequiresLogin() throws Exception {
        mvc.perform(get("/api/session-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test void expiredTokenRequiresNewLogin() throws Exception {
        mvc.perform(get("/api/session-test").header("Authorization", "Bearer " + token(Instant.now().minusSeconds(60))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.path").value("/api/session-test"));
        verifyNoInteractions(users);
    }

    @Test void invalidTokenRequiresNewLogin() throws Exception {
        mvc.perform(get("/api/session-test").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test void publicEndpointRemainsAvailableWithExpiredToken() throws Exception {
        mvc.perform(get("/auth/session-test").header("Authorization", "Bearer " + token(Instant.now().minusSeconds(60))))
                .andExpect(status().isOk()).andExpect(content().string("public"));
    }

    @Test void validTokenAllowsAccess() throws Exception {
        when(users.findByUsername("user@example.com")).thenReturn(User.builder()
                .roles(Set.of(new Role(1L, "USER"))).build());
        mvc.perform(get("/api/session-test").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(60))))
                .andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    @Test void authenticatedUserWithoutPermissionGetsForbidden() throws Exception {
        when(users.findByUsername("user@example.com")).thenReturn(User.builder().roles(Set.of()).build());
        mvc.perform(get("/api/session-test").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(60))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

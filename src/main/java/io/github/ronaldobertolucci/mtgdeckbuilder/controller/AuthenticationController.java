package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.ResendVerificationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.TokenDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.LoginDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserRegistrationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailVerificationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.RefreshTokenService;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.RefreshCookie;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RefreshAuthenticationException;
import org.springframework.http.HttpHeaders;
import java.util.Map;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookie refreshCookie;
    private final UserService userService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/login")
    public ResponseEntity<TokenDto> login(@RequestBody @Valid LoginDto loginDto) {
        var authenticationToken = new UsernamePasswordAuthenticationToken(
                loginDto.email(),
                loginDto.password()
        );

        Authentication authentication = authenticationManager.authenticate(authenticationToken);
        User user = (User) authentication.getPrincipal();

        return grant(refreshTokens.create(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenDto> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        return grant(refreshTokens.rotate(token));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        refreshTokens.revoke(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private ResponseEntity<TokenDto> grant(RefreshTokenService.Grant grant) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                        refreshCookie.create(grant.refreshToken(), grant.expiresAt()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(grant.access());
    }

    @ExceptionHandler(RefreshAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> invalidRefresh(RefreshAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("status", 401, "error", "Unauthorized", "code", "SESSION_EXPIRED",
                        "message", exception.getMessage()));
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody @Valid UserRegistrationDto registrationDto) {
        UserDto userDto = userService.register(registrationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(userDto);
    }

    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        emailVerificationService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody @Valid ResendVerificationDto dto) {
        emailVerificationService.resendVerificationEmail(dto.email());
        return ResponseEntity.ok().build();
    }
}
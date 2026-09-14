package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.ResendVerificationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.TokenDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.LoginDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.user.UserRegistrationDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.email.EmailVerificationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final TokenService tokenService;
    private final UserService userService;
    private final EmailVerificationService emailVerificationService;

    @Value("${api.security.token.expiration-hours:2}")
    private int expirationHours;

    @PostMapping("/login")
    public ResponseEntity<TokenDto> login(@RequestBody @Valid LoginDto loginDto) {
        var authenticationToken = new UsernamePasswordAuthenticationToken(
                loginDto.email(),
                loginDto.password()
        );

        Authentication authentication = authenticationManager.authenticate(authenticationToken);
        User user = (User) authentication.getPrincipal();

        String token = tokenService.generateToken(user);

        UserDto userDto = new UserDto(user);
        Long expiresInSeconds = expirationHours * 3600L;
        TokenDto tokenDto = new TokenDto(token, expiresInSeconds, userDto);

        return ResponseEntity.ok(tokenDto);
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
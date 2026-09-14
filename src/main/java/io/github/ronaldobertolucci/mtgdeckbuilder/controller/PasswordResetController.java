package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.MessageResponseDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.PasswordResetTokenForgotDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.security.PasswordResetTokenResetDto;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/forgot")
    public ResponseEntity<MessageResponseDto> forgotPassword(
            @RequestBody @Valid PasswordResetTokenForgotDto dto) {

        passwordResetService.createPasswordResetToken(dto.email());

        // Sempre retorna sucesso (não revela se email existe)
        return ResponseEntity.ok(
                new MessageResponseDto("If the email exists, a password reset link has been sent")
        );
    }

    @GetMapping("/reset/validate")
    public ResponseEntity<MessageResponseDto> validateToken(@RequestParam String token) {
        passwordResetService.validatePasswordResetToken(token);
        return ResponseEntity.ok(new MessageResponseDto("Token is valid"));
    }

    @PostMapping("/reset")
    public ResponseEntity<MessageResponseDto> resetPassword(
            @RequestBody @Valid PasswordResetTokenResetDto dto) {

        passwordResetService.resetPassword(dto.token(), dto.newPassword());
        return ResponseEntity.ok(new MessageResponseDto("Password has been reset successfully"));
    }
}
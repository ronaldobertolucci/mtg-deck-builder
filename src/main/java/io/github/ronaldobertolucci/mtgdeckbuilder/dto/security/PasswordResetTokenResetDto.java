package io.github.ronaldobertolucci.mtgdeckbuilder.dto.security;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetTokenResetDto(
        @NotBlank
        String token,
        @NotBlank
        String newPassword
) {
}
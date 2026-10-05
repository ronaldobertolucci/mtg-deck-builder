package io.github.ronaldobertolucci.mtgdeckbuilder.dto.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetTokenResetDto(
        @NotBlank
        String token,
        @NotBlank
        @Size(min = 8, message = "Password must have at least 8 characters")
        String newPassword
) {
}

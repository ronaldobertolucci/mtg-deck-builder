package io.github.ronaldobertolucci.mtgdeckbuilder.dto.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetTokenForgotDto(
        @Email(message = "Email must be valid")
        @NotBlank(message = "Email is required")
        String email
) {
}
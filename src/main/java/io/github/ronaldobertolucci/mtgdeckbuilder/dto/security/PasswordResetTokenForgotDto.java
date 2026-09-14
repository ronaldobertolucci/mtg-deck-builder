package io.github.ronaldobertolucci.mtgdeckbuilder.dto.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetTokenForgotDto(
        @Email
        @NotBlank
        String email
) {
}
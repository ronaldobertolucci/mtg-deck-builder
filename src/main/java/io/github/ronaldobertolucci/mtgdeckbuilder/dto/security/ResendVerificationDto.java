package io.github.ronaldobertolucci.mtgdeckbuilder.dto.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationDto(
        @NotBlank(message = "Email is required") @Email(message = "Email must be valid") String email
) {}
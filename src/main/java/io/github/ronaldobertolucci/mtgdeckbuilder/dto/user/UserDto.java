package io.github.ronaldobertolucci.mtgdeckbuilder.dto.user;


import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

public record UserDto(
        Long id,
        String firstName,
        String lastName,
        String email,
        LocalDate dateOfBirth,
        Boolean enabled,
        Set<String> roles
) {
    public UserDto(User user) {
        this(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getDateOfBirth(),
                user.getEnabled(),
                user.getRoles().stream()
                        .map(Role::getName)
                        .collect(Collectors.toSet())
        );
    }
}
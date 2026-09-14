package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleRepositoryTest extends BaseRepositoryTest {

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void findByName_WhenRoleDoesNotExist_ShouldReturnEmpty() {
        // Act
        Optional<Role> result = roleRepository.findByName("NONEXISTENT");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void findByName_ShouldBeCaseSensitive() {
        // Act
        Optional<Role> upperCase = roleRepository.findByName("USER");
        Optional<Role> lowerCase = roleRepository.findByName("user");

        // Assert
        assertTrue(upperCase.isPresent());
        assertTrue(lowerCase.isEmpty());
    }
}